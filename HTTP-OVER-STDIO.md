# HTTP over stdio transport

`mcp-transport-http-over-stdio` runs the MCP 2026-07-28 Streamable HTTP binding over a child process's stdin and stdout. A host launches a local MCP server as a subprocess, as it does with the existing stdio transport, but the bytes on the pipe are HTTP/2 instead of newline-delimited JSON-RPC. The local server therefore has the same protocol surface as a remote one: the same request headers, status codes, per-request SSE streams, and OAuth protected-resource behavior.

This document explains how the transport is put together, which design choices are not obvious, and how well the approach fits the Java SDK. It does not cover implementation details such as threading or buffer ownership.

## Motivation

MCP has two transport bindings with different semantics. The stdio binding is a single ordered stream of JSON-RPC messages with no metadata around them. The Streamable HTTP binding carries meaning outside the JSON body: `Mcp-Method`, `Mcp-Name`, and `Mcp-Param-*` headers mirror the body so intermediaries can route without parsing it, errors map to HTTP status codes, each request can return its own SSE stream, and authorization uses `WWW-Authenticate` challenges and RFC 9728 metadata. A server written for one binding is not automatically correct on the other.

Carrying HTTP over the process pipe gives local servers a single binding. A server is written once against Streamable HTTP and can be deployed either way, and the host uses one client implementation and one authorization path for both.

## Architecture

The transport has four layers. Each layer only uses the layer directly below it.

```
host process                                         child process
------------                                         -------------
McpClient                                            McpServer (stateless)
HttpClientStreamableHttpTransport (unchanged SDK)    HttpOverStdioServerTransport
PipeHttpClient (a java.net.http.HttpClient)          Streamable HTTP dispatch + 2026 header binding
HTTP/2 client (Netty)                                HTTP/2 server (Netty)
pipe channel  ------- child stdin / stdout -------   pipe channel
```

### Byte pipe

The bottom layer is a pair of byte streams: the child's stdin and stdout as seen from the host, or `System.in` and the raw stdout descriptor as seen from the child. These are blocking `InputStream` and `OutputStream` objects, not selectable channels, so Netty's normal socket transports cannot use them. The module provides a small custom Netty `Channel` that copies bytes between the streams and the Netty pipeline. Half-close is supported: closing the host's output closes the child's stdin, which is the child's signal to exit.

A Unix-domain-socket implementation of the same pipe interface is included as a fallback wire for environments where process pipes are unsuitable. It passes the same pipe tests.

### HTTP/2 framing

On top of the pipe, Netty's HTTP/2 codec runs in prior-knowledge mode (often called h2c): the client sends the HTTP/2 connection preface directly, with no TLS and no HTTP/1.1 upgrade. Every MCP request is one HTTP/2 stream. This provides several things that a raw pipe does not:

- Concurrent requests multiplex over one byte stream, and a slow or long-lived response does not block others.
- Each request has its own response, and that response may be a single JSON body, an SSE stream, or a 202 with no body.
- Cancelling one request resets only its stream (`RST_STREAM`). The connection and its other requests are unaffected.
- Shutdown is explicit: `GOAWAY` tells the peer that no new streams will be opened, and in-flight streams can finish before the pipe closes.

### Client side

The SDK's existing `HttpClientStreamableHttpTransport` sends every request through a `java.net.http.HttpClient`. `HttpClient` is an abstract class, and the transport calls only `sendAsync`. The module provides `PipeHttpClient`, an `HttpClient` subclass that turns each `HttpRequest` into an HTTP/2 stream on the pipe and returns an ordinary `HttpResponse`. It is passed to the transport through the builder hook that already exists:

```java
HttpOverStdioClientTransport child = HttpOverStdioClientTransport
	.launch(List.of("java", "-jar", "server.jar"), Map.of(), System.err::println)
	.get();

McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("https://child.invalid:8443")
		.clientBuilder(child.httpClient().asClientBuilder())
		.build())
	.build();
```

Everything above the HTTP client stays the same as for a remote server: request customizers, the authorization error handler, response classification, and SSE parsing.

### Server side

`HttpOverStdioServerTransport` implements the SDK's existing `McpStatelessServerTransport`, so it plugs into `McpServer.sync(transport)` or `McpServer.async(transport)` like the servlet transport does. For each HTTP/2 request it:

1. Validates `Origin`, and checks that `:authority` is one the child serves.
2. Routes the request by path. The MCP endpoint goes to MCP dispatch; any other path goes to an optional application route (for example `/.well-known/oauth-protected-resource/mcp`) or gets 404.
3. Optionally runs an authorizer that can answer 401 with a `WWW-Authenticate` challenge.
4. Dispatches the message to the SDK handler and writes the result as JSON, SSE, or 202.

Dispatch enforces the 2026-07-28 binding. Each request's headers must match its body: `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`, and an `Mcp-Param-*` header for every argument the tool's input schema annotates with `x-mcp-header`. A mismatch gets HTTP 400 with `HeaderMismatch` (-32020), an unsupported version gets `UnsupportedProtocolVersion` (-32022), and an unknown method gets 404. GET and DELETE at the endpoint get 405, because the 2026 binding has no session streams.

A child serving its own stdio reserves stdout for HTTP/2 frames. `builderOnCurrentProcessStdio()` writes frames to the raw stdout file descriptor and points `System.out` at `System.err`. This matters in the Java ecosystem, where logging frameworks commonly write to `System.out` by default. A single stray line on stdout corrupts the HTTP/2 connection.

### Logical origins and authorization

The host addresses the child by a logical origin, for example `https://child.invalid:8443`. The origin is never resolved or connected to. Its scheme and authority only become the HTTP/2 `:scheme` and `:authority` pseudo-headers. The child sees a normal HTTPS authority, validates it, and builds its OAuth resource identifier from it, so RFC 9728 discovery and audience-restricted tokens work as they would against a remote server.

Authorization involves network traffic too: the host still has to reach the real authorization server. `LogicalAuthorityRoutingHttpClient` is an `HttpClient` that sends requests for configured child origins to that child's pipe, and every other request to a normal network client. It fails closed:

- A child-bound request never falls back to the network.
- A child's host name under a different scheme or port is rejected rather than resolved through DNS.
- Responses, including redirects, are returned without being followed, so a child-scoped token is never replayed to another origin.

## Design tradeoffs

### HTTP/2 rather than HTTP/1.1

HTTP/1.1 over one byte stream would process requests strictly in order. A single long-lived SSE response, such as `subscriptions/listen`, would block every later request. Multiplexing requires HTTP/2. The cost is a binary framing layer: the pipe can no longer be read by eye, and every message carries a small framing overhead, which is not significant for MCP traffic.

### Netty as a dependency

The JDK has an HTTP/2 client, but it only runs over sockets it opens itself, and the JDK has no HTTP/2 server. Running HTTP/2 over an arbitrary pair of streams therefore needs a third-party HTTP/2 implementation, and Netty's is the most widely used on the JVM. That makes the module depend on several Netty artifacts. The dependency is confined to the new module, so `mcp-core` and its users are unaffected.

`java.net.http.HttpClient` still appears on the client side, but only as an abstract base class. `PipeHttpClient` replaces the JDK implementation entirely and uses Netty underneath.

### Blocking process pipes under an asynchronous stack

Process stdin and stdout in Java are blocking streams, and on Windows they cannot be made selectable. The pipe layer therefore adapts blocking reads and writes to Netty's asynchronous model, at the cost of dedicated threads per connection instead of a shared selector. With one connection per child process, the cost is small.

### Substituting `HttpClient` instead of changing the SDK

Subclassing `HttpClient` lets the SDK's client transport run unchanged. The tradeoff is that it relies on two facts that are not formal contracts. First, the transport uses only `sendAsync`. Second, it accepts any `HttpClient` from its `clientBuilder` hook. Both hold today, and the transport's own tests would catch a change. The subclass ignores HTTP client settings that make no sense for a pipe, such as proxies, connect timeouts, and redirects. The JDK request builder also does not allow restricted headers such as `Host`; that is not a problem here, because the URI determines `:authority`.

### Keeping the 2026 binding inside the module

The header binding, its value encoding, and the `x-mcp-header` schema rules are general Streamable HTTP server logic, and the SDK's servlet transport will need them too. They live in this module to keep the change self-contained, so the servlet transport does not enforce the 2026 binding. Moving them into `mcp-core` later would let both transports share one implementation.

### Stateless only

The transport targets the 2026-07-28 binding, which is stateless: there are no `Mcp-Session-Id` sessions, no GET streams, and no `Last-Event-ID` resumption. Servers built on the SDK's session-based server API are not supported over this transport.

## How well the approach fits the Java SDK

The transport mechanics fit well. The SDK's existing client transport and stateless server API run over the pipe without modification. The SDK's shared stateless integration suite passes over the pipe unchanged. The official conformance runner is driven through a loopback bridge in `conformance-tests/server-http-over-stdio`. In that run every transport and HTTP-binding check is enforced and passes. The 57 checks that still fail are listed one by one in a baseline file, and each one comes from a gap in SDK core.

The remaining gaps are in SDK core, not in the transport:

- The client does not compute the 2026 request headers (`Mcp-Method`, `Mcp-Name`, `Mcp-Param-*`) or add `_meta.io.modelcontextprotocol/protocolVersion` to requests. Callers must supply them, and the SDK client still begins with an `initialize` handshake. End-to-end runs with the real `McpClient` therefore disable header enforcement on the server.
- Stateless server handlers return a single response and cannot emit progress or log notifications. Request-scoped SSE streams are only possible through the module's own `McpStatelessStreamingServerHandler`, which bypasses the SDK's server API. `subscriptions/listen` is not implemented.
- 2026 result fields (`resultType`, `cacheScope`, `ttlMs`), `server/discover`, request `_meta` validation, and multi-round input requests are missing. These make up most of the remaining conformance failures.

Linux has been tested. macOS and Windows are covered by a CI matrix that has not yet run.

## Changes to existing public interfaces

None. No existing public type, method, or behavior in `mcp-core`, `mcp`, or the JSON modules changed. The only edits to existing files add the new modules to the root `pom.xml` and to `conformance-tests/pom.xml`. The client side uses the pre-existing `HttpClientStreamableHttpTransport.Builder.clientBuilder(HttpClient.Builder)` hook, and the server side implements the pre-existing `McpStatelessServerTransport` interface.
