# HTTP over stdio transport

`mcp-transport-http-over-stdio` runs the MCP Streamable HTTP binding over a child process's stdin and stdout. A host launches a local MCP server as a subprocess, as it does with the existing stdio transport, but the bytes on the pipe are HTTP/2 instead of newline-delimited JSON-RPC. The local server therefore has the same protocol surface as a remote one: the same request headers, status codes, per-request SSE streams, and OAuth protected-resource behavior.

The module is a proof of concept intended to inform community discussion, not a component meant for production use. Its purpose is to show that the approach can be built on the Java SDK as it exists today, as one of the language ecosystems MCP targets, and to identify where it fits well and where it does not.

This document explains how the transport is put together, which design choices are not obvious, and how well the approach fits the Java SDK. It does not cover implementation details such as threading or buffer ownership.

## Motivation

MCP has two transport bindings with different semantics. The stdio binding is a single ordered stream of JSON-RPC messages with no metadata around them. The Streamable HTTP binding carries meaning outside the JSON body: headers that mirror the body so intermediaries can route without parsing it, errors mapped to HTTP status codes, a separate SSE stream per request, and authorization through `WWW-Authenticate` challenges and RFC 9728 metadata. A server written for one binding is often not correct on the other. This is most visible when a server written for stdio is later exposed over HTTP: assumptions that held on a private, ordered, single-client pipe, such as having no authorization, no concurrent requests, and no request-scoped streams, stop holding.

Carrying HTTP over the process pipe gives local servers a single binding. A server is written once against Streamable HTTP and can be deployed either way, and the host uses one client implementation and one authorization path for both.

A single binding also simplifies the evolution of the protocol specification. Today a feature that touches transport behavior has to be specified twice, once in HTTP terms and once in terms of the stdio message stream, and some semantics are duplicated from the JSON payload into headers so that HTTP infrastructure can see them. If every transport carries HTTP semantics, new features can be defined once, in terms of HTTP. Part of the reason for this proof of concept is to evaluate that as a direction for MCP as a whole.

## Architecture

The transport has four layers. Each layer only uses the layer directly below it.

```
host process                                         child process
------------                                         -------------
McpClient                                            McpServer (stateless)
HttpClientStreamableHttpTransport (unchanged SDK)    HttpOverStdioServerTransport
PipeHttpClient (a java.net.http.HttpClient)          Streamable HTTP dispatch
HTTP/2 client (Netty)                                HTTP/2 server (Netty)
pipe channel  ------- child stdin / stdout -------   pipe channel
```

### Byte pipe

The bottom layer is a pair of byte streams: the child's stdin and stdout as seen from the host, or `System.in` and the raw stdout descriptor as seen from the child. These are blocking `InputStream` and `OutputStream` objects, not selectable channels, so Netty's normal socket transports cannot use them. The module provides a small custom Netty `Channel` that copies bytes between the streams and the Netty pipeline. Half-close is supported: closing the host's output closes the child's stdin, which is the child's signal to exit.

Over process pipes, the transport only reaches servers that the host launches itself and whose stdout carries nothing but HTTP/2 frames. A local server that is already running, such as a shared daemon or sidecar, cannot be reached this way. Neither can a server started through a launcher or wrapper script that prints to stdout first, or one that loads native code writing directly to file descriptor 1, which redirecting `System.out` does not affect. These are uncommon, and server developers can reasonably be expected to keep stdout clean.

### HTTP/2 framing

On top of the pipe, Netty's HTTP/2 codec runs with prior knowledge: the client sends the HTTP/2 connection preface as its first bytes, without TLS and without an HTTP/1.1 upgrade request.

Prior knowledge is possible because the host controls both ends. It launches the child specifically to speak HTTP/2, so there is nothing to negotiate. TLS is unnecessary because the pipe is private to the two processes and never crosses a network. It is also the reason the transport supplies its own HTTP/2 client: the JDK's `HttpClient` can only reach cleartext HTTP/2 through the upgrade request and does not support prior knowledge, in addition to only connecting to sockets it opens itself.

Every MCP request is one HTTP/2 stream. This provides several things that a raw pipe does not:

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
4. Dispatches the message to the SDK handler and writes the result as JSON, SSE, or 202. GET and DELETE at the endpoint get 405, because the stateless binding has no session streams.

Request headers reach the server unchanged, but the transport does not check that headers which mirror the body actually match it. That check is general Streamable HTTP server logic that the servlet transport needs too, so it belongs in `mcp-core`, where both transports can share it, and is outside the scope of a transport.

A child serving its own stdio reserves stdout for HTTP/2 frames. `builderOnCurrentProcessStdio()` writes frames to the raw stdout file descriptor and points `System.out` at `System.err`. This matters in the Java ecosystem, where logging frameworks commonly write to `System.out` by default. A single stray line on stdout corrupts the HTTP/2 connection.

### Logical origins and authorization

The host addresses the child by a logical origin, for example `https://child.invalid:8443`. The origin is never resolved or connected to. Its scheme and authority only become the HTTP/2 `:scheme` and `:authority` pseudo-headers. The child sees a normal HTTPS authority, validates it, and builds its OAuth resource identifier from it, so RFC 9728 discovery and audience-restricted tokens work as they would against a remote server.

Authorization involves network traffic too: the host still has to reach the real authorization server. `LogicalAuthorityRoutingHttpClient` is an `HttpClient` that sends requests for configured child origins to that child's pipe, and every other request to a normal network client. It fails closed:

- A child-bound request never falls back to the network.
- A child's host name under a different scheme or port is rejected rather than resolved through DNS.
- Responses, including redirects, are returned without being followed, so a child-scoped token is never replayed to another origin.

## Design tradeoffs

### HTTP/2 rather than HTTP/1.1

HTTP/1.1 over one byte stream processes requests strictly in order. A single long-lived SSE response, such as `subscriptions/listen`, would block every later request. Multiplexing could be added to HTTP/1.1 with a custom envelope that tags each message with a stream identifier, but that would rebuild a less capable version of what HTTP/2 already standardizes, including per-stream flow control, cancellation, and graceful shutdown, and every language would have to implement the envelope itself. The cost of HTTP/2 is a binary framing layer: the pipe can no longer be read by eye, and every message carries a small framing overhead, which is not significant for MCP traffic.

### Netty as a dependency

The JDK has an HTTP/2 client, but it only runs over sockets it opens itself and does not support prior knowledge, and the JDK has no HTTP/2 server. Running HTTP/2 over an arbitrary pair of streams therefore needs a third-party HTTP/2 implementation, and Netty's is the most widely used on the JVM. The module depends on `netty-codec-http2`, `netty-handler`, `netty-transport`, and `netty-buffer`, and their transitive Netty modules. `mcp-core` and its users are unaffected unless they add this module.

The module uses only Netty's pure-Java codec and pipeline: no native epoll or kqueue transport, no sockets, and no server bootstrap. It therefore runs anywhere a JVM can spawn processes, with no platform-specific artifacts.

Netty can run alongside other HTTP stacks in the same process, including Jetty, Tomcat, Undertow, OkHttp, and the JDK client. It does not install JVM-wide handlers or take over process-wide resources, so it does not conflict with them. The practical risks are about versions and policy rather than coexistence:

- Netty is already on the classpath of many JVM services, through Reactor Netty (Spring WebFlux), gRPC, Vert.x and Quarkus, the AWS SDK's asynchronous clients, and many database drivers. Those services cannot have two Netty versions on one classpath, so the module's Netty version is resolved against theirs. Netty's modules must also be kept on matching versions, which Netty's bill of materials handles. The SDK's dependency policy of avoiding routine version bumps keeps this manageable, but it does not remove it.
- Some deployments enforce strict dependency allowlists or require every dependency to be reviewed, and Netty is a large dependency to add for a single transport. Shading Netty into the module, as gRPC does with `grpc-netty-shaded`, avoids version conflicts at the cost of a larger artifact and of security fixes that only arrive with a new module release.
- On JDK 24 and later, Netty 4.1, which this module uses, calls memory-access methods of `sun.misc.Unsafe`. These print a runtime warning (JEP 498) unless the JVM is started with `--sun-misc-unsafe-memory-access=allow`. Newer Netty 4.2 releases can avoid `Unsafe`, but only on specific JDK versions or with further JVM flags. This affects every Netty user equally rather than this module in particular, but a library cannot set JVM flags for the application that embeds it.

The alternative is an HTTP/2 implementation written for the module. HTTP/2 framing over a single trusted peer is a much smaller problem than a general-purpose HTTP/2 stack, since it needs no TLS, no connection pooling, and no defense against arbitrary clients, but it still involves HPACK, flow control, and stream lifecycle, and would be new code to maintain. Owning a protocol implementation is a long-term maintenance and security commitment, so reusing an established HTTP/2 stack is preferable where the dependency is acceptable. Weighing that against the dependency costs above is a decision for the SDK maintainers.

### Blocking process pipes under an asynchronous stack

Process stdin and stdout in Java are blocking streams, and on Windows they cannot be made selectable. The pipe layer therefore adapts blocking reads and writes to Netty's asynchronous model, at the cost of dedicated threads per connection instead of a shared selector. With one connection per child process, the cost is small.

### Substituting `HttpClient` instead of changing the SDK

Subclassing `HttpClient` lets the SDK's client transport run unchanged. The tradeoff is that it relies on two facts that are not formal contracts. First, the transport uses only `sendAsync`. Second, it accepts any `HttpClient` from its `clientBuilder` hook. Both hold today, and the transport's own tests would catch a change. The subclass ignores HTTP client settings that make no sense for a pipe, such as proxies, connect timeouts, and redirects. The JDK request builder also does not allow restricted headers such as `Host`; that is not a problem here, because the URI determines `:authority`.

### Stateless only

The transport targets the stateless Streamable HTTP binding: there are no `Mcp-Session-Id` sessions, no GET streams, and no `Last-Event-ID` resumption. Servers built on the SDK's session-based server API are not supported over this transport.

## How well the approach fits the Java SDK

The transport mechanics fit well. The SDK's existing client transport and stateless server API run over the pipe without modification. The SDK's shared stateless integration suite passes over the pipe unchanged. The official conformance runner is driven through a loopback bridge in `conformance-tests/server-http-over-stdio`, and every check that exercises the transport itself passes, including multiple concurrent SSE streams, Origin and Host validation, and the HTTP status mapping.

The remaining gaps are all in SDK core and are independent of the transport:

- The client does not yet compute the request headers that mirror the body.
- The server does not yet validate those headers against the body.
- Stateless server handlers cannot stream notifications, so request-scoped SSE responses need the module's own handler interface.
- Newer protocol features such as `server/discover` and the newer result fields are not implemented.

The transport has been tested on Linux only. On Windows, process pipes are not selectable, which the blocking pipe adapter already accounts for. On macOS, the default pipe buffer is smaller than on Linux, which may reduce throughput but not correctness.

## Changes to existing public interfaces

No existing public interface had to change.
