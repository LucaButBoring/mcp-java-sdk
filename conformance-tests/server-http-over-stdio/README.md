# MCP Conformance Tests - HTTP over stdio Server

This module runs the official [MCP conformance suite](https://github.com/modelcontextprotocol/conformance) against a stateless SDK server that is reachable only over HTTP/2 on a child process's stdin and stdout (`mcp-transport-http-over-stdio`).

The conformance runner can only test a server at a URL, so `StdioBridge` launches `ConformanceStdioServer` as a child process and serves HTTP/1.1 on the loopback interface. It forwards each request unchanged to the child: method, path, query, body, and every header except hop-by-hop ones (the fixed set plus any header the message's `Connection` field names). `Host` becomes the HTTP/2 `:authority`. The child's response streams back with the same filtering, and a failure before the response starts (child exit, timeout) answers 502. Every MCP decision, including Origin and Host validation, is made by the child.

The bridge has no authentication and binds to 127.0.0.1 only. It is a test harness, not a deployment component.

`StdioBridgeTests` checks the bridge mechanics directly:

- `Host` is forwarded as the `:authority`.
- Headers named by `Connection` are dropped.
- A notification gets 202 with no body.
- A child that dies answers 502.
- `close()` stops both the front end and the child.

The child's MCP responses are always JSON, because the stateless server never returns SSE. SSE passthrough is therefore exercised only by the runner's `server-sse-multiple-streams` scenario.

## Running

Build the SDK modules, then start the bridge:

```bash
./mvnw -q install -DskipTests -pl mcp-core,mcp,mcp-json-jackson3,mcp-transport-http-over-stdio
./mvnw -q -pl conformance-tests/server-http-over-stdio compile exec:exec -Dbridge.port=3002
```

Use `exec:exec`, not `exec:java`: the bridge launches the child with its own `java.class.path`, which is only the project classpath in a separate JVM.

In another terminal, run the 2026-07-28 suite with the baseline of known SDK-core gaps:

```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.12 server \
  --url http://localhost:3002/mcp --suite all --spec-version 2026-07-28 \
  --expected-failures conformance-tests/server-http-over-stdio/conformance-baseline-2026-07-28.yml
```

The run exits 0 when only baselined checks fail. It exits 1 on any new failure and on any stale baseline entry. `StdioBridgeTests` covers the bridge itself without npm.

## Results

Measured with conformance runner `0.2.0-alpha.12` at spec version 2026-07-28: 53 checks pass and 76 fail. Every check that exercises the transport passes:

- `server-sse-multiple-streams`
- `dns-rebinding-protection`
- Origin, Host, and endpoint handling

Each failure is listed per check in `conformance-baseline-2026-07-28.yml`. None of them comes from the transport:

- Header and request-metadata validation (`http-header-validation`, `http-custom-header-server-validation`, and the related `server-stateless` checks) is deliberately not implemented here. It is general Streamable HTTP server logic that belongs in `mcp-core`.
- 2026 result fields (`resultType`, `cacheScope`, `ttlMs`) are not emitted.
- SEP-2549 caching hints are not emitted.
- `server/discover` and the 2026 method inventory are not implemented.
- MRTR (`InputRequiredResult`) is not implemented.
- Request-scoped progress is not supported. Stateless handlers have no notification sink, so `test_tool_with_progress` is omitted from the fixture.

Because the baseline is per check, a transport regression inside any listed scenario still fails the run.

Tools that need a session exchange (sampling, elicitation, logging, progress) are omitted from the fixture for the same reason.
