package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.client.transport.http.McpHttpResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.http.SseEvent;
import io.modelcontextprotocol.server.transport.http.StatelessHttpResponse;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

class HttpOverStdioServerTransportTests {

	private static final String V = ProtocolVersions.MCP_2026_07_28;

	private static final String LIST_BODY = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\","
			+ "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + V + "\"}}}";

	private DefaultEventLoopGroup serverGroup;

	private HttpOverStdioServerTransport server;

	private HttpOverStdioClientTransport client;

	@AfterEach
	void tearDown() throws Exception {
		if (this.client != null) {
			this.client.closeGracefully().block(Duration.ofSeconds(10));
		}
		if (this.server != null) {
			this.server.closeGracefully().block(Duration.ofSeconds(10));
		}
		if (this.serverGroup != null) {
			this.serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
		}
	}

	/**
	 * Assembling the close pipeline must not start it, and running it must deliver GOAWAY
	 * before closing. Mono.fromFuture(future) used to fire every step at assembly.
	 */
	@Test
	void closeGracefullyIsLazyOnBothEndsAndDeliversGoAway() throws Exception {
		connect(builder -> {
		});
		Mono<Void> clientClose = this.client.closeGracefully();
		Mono<Void> serverClose = this.server.closeGracefully();
		Thread.sleep(200);
		assertThat(this.server.server().channel().isOpen()).as("server touched by assembly").isTrue();
		assertThat(this.server.server().goAwayReceived()).as("client GOAWAY sent by assembly").isNotDone();

		clientClose.block(Duration.ofSeconds(10));
		this.server.server().goAwayReceived().get(5, TimeUnit.SECONDS);
		serverClose.block(Duration.ofSeconds(10));
	}

	@Test
	void legacyHandlerRoundTripsAJsonRpcRequestWith2026Headers() throws Exception {
		connect(builder -> {
		});
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		McpHttpResponse response = send(post("/mcp", LIST_BODY, mcpHeaders("tools/list")));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(read(response)).contains("\"id\":\"1\"").contains("\"result\":\"ok\"");
		assertThat(this.server.protocolVersions()).containsExactly(V);
	}

	@Test
	void requestsBeforeAHandlerIsInstalledAnswer503() throws Exception {
		connect(builder -> {
		});
		assertThat(send(post("/mcp", LIST_BODY, mcpHeaders("tools/list"))).statusCode()).isEqualTo(503);
	}

	@Test
	void headerMismatchAnswers400WithMinus32020() throws Exception {
		connect(builder -> {
		});
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		McpHttpResponse response = send(post("/mcp", LIST_BODY, mcpHeaders("tools/call")));
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(read(response)).contains("\"code\":-32020").contains("Mcp-Method");
	}

	@Test
	void getAndDeleteAtTheEndpointAnswer405() throws Exception {
		connect(builder -> {
		});
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		for (String method : List.of("GET", "DELETE")) {
			assertThat(send(request(method, "/mcp", null, mcpHeaders("tools/list"))).statusCode()).as(method)
				.isEqualTo(405);
		}
	}

	@Test
	void otherPathsGoToTheApplicationRouteOr404() throws Exception {
		connect(builder -> {
		});
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		assertThat(send(request("GET", "/.well-known/oauth-protected-resource/mcp", null, Map.of())).statusCode())
			.isEqualTo(404);
		this.client.closeGracefully().block(Duration.ofSeconds(10));
		this.server.closeGracefully().block(Duration.ofSeconds(10));

		connect(builder -> builder.applicationRoute((request, writer) -> {
			writer.headers("200", new DefaultHttp2Headers().add("content-type", "application/json"));
			writer.data(io.netty.buffer.Unpooled.copiedBuffer("{\"resource\":\"" + request.path() + "\"}",
					StandardCharsets.UTF_8), true);
		}));
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		McpHttpResponse metadata = send(request("GET", "/.well-known/oauth-protected-resource/mcp", null, Map.of()));
		assertThat(metadata.statusCode()).isEqualTo(200);
		assertThat(read(metadata)).contains("/.well-known/oauth-protected-resource/mcp");
		assertThat(send(post("/mcp?x=1", LIST_BODY, mcpHeaders("tools/list"))).statusCode())
			.as("query does not change endpoint matching")
			.isEqualTo(200);
	}

	@Test
	void presentOriginIsRejectedByDefaultAndAuthoritiesAreExact() throws Exception {
		connect(builder -> builder.allowedAuthorities(List.of("child.example:8443")));
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		Map<String, String> withOrigin = new java.util.LinkedHashMap<>(mcpHeaders("tools/list"));
		withOrigin.put("Origin", "https://evil.example");
		assertThat(send(post("https://child.example:8443/mcp", LIST_BODY, withOrigin)).statusCode()).isEqualTo(403);
		assertThat(send(post("https://child.example:8443/mcp", LIST_BODY, mcpHeaders("tools/list"))).statusCode())
			.isEqualTo(200);
		assertThat(send(post("https://other.example:8443/mcp", LIST_BODY, mcpHeaders("tools/list"))).statusCode())
			.isEqualTo(421);
	}

	@Test
	void originIsValidatedBeforeRouteSelectionAndBeforeAHandlerIsInstalled() throws Exception {
		connect(builder -> builder.applicationRoute((request, writer) -> {
			writer.headers("200", new DefaultHttp2Headers());
			writer.end();
		}));
		Map<String, String> hostile = new java.util.LinkedHashMap<>(mcpHeaders("tools/list"));
		hostile.put("Origin", "https://evil.example");
		assertThat(send(post("/mcp", LIST_BODY, hostile)).statusCode()).as("before setMcpHandler").isEqualTo(403);
		McpHttpResponse routed = send(request("GET", "/.well-known/oauth-protected-resource/mcp", null, hostile));
		assertThat(routed.statusCode()).as("application route").isEqualTo(403);
		assertThat(routed.headers().firstValue("content-type")).contains("application/json");
		assertThat(read(routed)).isEqualTo("Invalid Origin header");
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		McpHttpResponse dispatched = send(post("/mcp", LIST_BODY, hostile));
		assertThat(dispatched.statusCode()).isEqualTo(403);
		assertThat(dispatched.headers().firstValue("content-type")).contains("application/json");
		assertThat(read(dispatched)).isEqualTo("Invalid Origin header");
		assertThat(send(request("GET", "/.well-known/oauth-protected-resource/mcp", null, Map.of())).statusCode())
			.isEqualTo(200);
	}

	@Test
	void compatibilityFactoriesRemainAvailable() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		this.server = HttpOverStdioServerTransport.start(pair[1], null, McpJsonDefaults.getMapper());
		this.client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
		this.server.setMcpHandler(okHandler(new AtomicReference<>()));
		assertThat(send(post("/mcp", LIST_BODY, mcpHeaders("tools/list"))).statusCode()).isEqualTo(200);
		// Calling it would take over this JVM's stdio, so only its public signature is
		// pinned.
		java.lang.reflect.Method stdio = HttpOverStdioServerTransport.class.getMethod("startOnCurrentProcessStdio",
				io.netty.channel.EventLoopGroup.class,
				io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig.class,
				io.modelcontextprotocol.json.McpJsonMapper.class);
		assertThat(java.lang.reflect.Modifier.isStatic(stdio.getModifiers())).isTrue();
		assertThat(stdio.getReturnType()).isEqualTo(HttpOverStdioServerTransport.class);
	}

	@Test
	void contextExtractorReachesTheHandler() throws Exception {
		connect(builder -> builder.contextExtractor(
				request -> McpTransportContext.create(Map.of("authority", request.headers().authority().toString()))));
		AtomicReference<McpTransportContext> seen = new AtomicReference<>();
		this.server.setMcpHandler(okHandler(seen));
		assertThat(send(post("https://child.example:8443/mcp", LIST_BODY, mcpHeaders("tools/list"))).statusCode())
			.isEqualTo(200);
		assertThat(seen.get().get("authority")).isEqualTo("child.example:8443");
	}

	@Test
	void sseResponseUsesServletWireFormatAndClientCancelDisposesTheFlux() throws Exception {
		CountDownLatch disposed = new CountDownLatch(1);
		Sinks.Many<SseEvent> live = Sinks.many().unicast().onBackpressureBuffer();
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		this.serverGroup = new DefaultEventLoopGroup();
		PipeHttp2Server raw = PipeHttp2Server.start(pair[1], this.serverGroup, null, (request, writer) -> {
			Flux<SseEvent> events = "/finite".equals(request.path())
					? Flux.just(new SseEvent(Optional.of("e1"), "message", "{\"a\":1}"),
							new SseEvent(Optional.empty(), "message", "{\"b\":2}"))
					: live.asFlux().doOnCancel(disposed::countDown);
			HttpOverStdioServerTransport.writeResponse(writer,
					new StatelessHttpResponse(200, Map.of(), new StatelessHttpResponse.Sse(events)),
					Duration.ofSeconds(30));
		});
		this.client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
		try {
			McpHttpResponse finite = send(request("GET", "/finite", null, Map.of()));
			assertThat(finite.headers().firstValue("content-type")).contains("text/event-stream");
			assertThat(read(finite))
				.isEqualTo("id: e1\nevent: message\ndata: {\"a\":1}\n\nevent: message\ndata: {\"b\":2}\n\n");

			McpHttpResponse open = send(request("GET", "/live", null, Map.of()));
			CountDownLatch first = new CountDownLatch(1);
			Disposable reading = JdkFlowAdapter.flowPublisherToFlux(open.body()).subscribe(chunk -> first.countDown());
			live.tryEmitNext(new SseEvent(Optional.empty(), "message", "{}"));
			assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
			reading.dispose();
			assertThat(disposed.await(5, TimeUnit.SECONDS)).as("client RST_STREAM must cancel the server Flux")
				.isTrue();
		}
		finally {
			raw.close().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void longLivedStreamsEmitKeepAliveCommentsOnlyWhileIdle() {
		Sinks.Many<String> frames = Sinks.many().unicast().onBackpressureBuffer();
		List<String> out = new java.util.concurrent.CopyOnWriteArrayList<>();
		Disposable subscription = HttpOverStdioServerTransport.withKeepAlive(frames.asFlux(), Duration.ofMillis(100))
			.subscribe(out::add);
		try {
			sleep(350);
			assertThat(out).hasSizeBetween(2, 4).allMatch(HttpOverStdioServerTransport.KEEP_ALIVE_COMMENT::equals);
			out.clear();
			for (int i = 0; i < 6; i++) {
				frames.tryEmitNext("event: message\ndata: {}\n\n");
				sleep(40);
			}
			assertThat(out).as("frames arriving faster than the interval suppress comments")
				.containsOnly("event: message\ndata: {}\n\n");
			frames.tryEmitComplete();
			out.clear();
			sleep(250);
			assertThat(out).as("no comments after completion").isEmpty();
		}
		finally {
			subscription.dispose();
		}
	}

	@Test
	void finiteStreamsDoNotEmitKeepAliveComments() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		this.serverGroup = new DefaultEventLoopGroup();
		Sinks.Many<SseEvent> live = Sinks.many().unicast().onBackpressureBuffer();
		PipeHttp2Server raw = PipeHttp2Server.start(pair[1], this.serverGroup, null, (request, writer) -> {
			boolean listen = "/listen".equals(request.path());
			Flux<SseEvent> idle = listen ? live.asFlux() : Flux.never();
			HttpOverStdioServerTransport.writeResponse(writer,
					new StatelessHttpResponse(200, Map.of(), new StatelessHttpResponse.Sse(idle, listen)),
					Duration.ofMillis(100));
		});
		this.client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
		try {
			McpHttpResponse listen = send(request("GET", "/listen", null, Map.of()));
			CountDownLatch comment = new CountDownLatch(1);
			Disposable reading = JdkFlowAdapter.flowPublisherToFlux(listen.body())
				.flatMapIterable(values -> values)
				.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
				.subscribe(text -> {
					if (text.equals(HttpOverStdioServerTransport.KEEP_ALIVE_COMMENT)) {
						comment.countDown();
					}
				});
			assertThat(comment.await(5, TimeUnit.SECONDS)).as("long-lived stream keep-alive").isTrue();
			reading.dispose();

			McpHttpResponse scoped = send(request("GET", "/scoped", null, Map.of()));
			List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
			Disposable scopedReading = JdkFlowAdapter.flowPublisherToFlux(scoped.body())
				.flatMapIterable(values -> values)
				.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
				.subscribe(seen::add);
			sleep(400);
			assertThat(seen).isEmpty();
			scopedReading.dispose();
		}
		finally {
			raw.close().get(5, TimeUnit.SECONDS);
		}
	}

	private void connect(Consumer<HttpOverStdioServerTransport.Builder> customizer) throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		if (this.serverGroup == null) {
			this.serverGroup = new DefaultEventLoopGroup();
		}
		HttpOverStdioServerTransport.Builder builder = HttpOverStdioServerTransport.builder(pair[1])
			.eventLoopGroup(this.serverGroup)
			.jsonMapper(McpJsonDefaults.getMapper());
		customizer.accept(builder);
		this.server = builder.build();
		this.client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
	}

	private static McpStatelessServerHandler okHandler(AtomicReference<McpTransportContext> seen) {
		return new McpStatelessServerHandler() {
			@Override
			public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
					McpSchema.JSONRPCRequest request) {
				seen.set(context);
				return Mono.just(McpSchema.JSONRPCResponse.result(request.id(), "ok"));
			}

			@Override
			public Mono<Void> handleNotification(McpTransportContext context,
					McpSchema.JSONRPCNotification notification) {
				return Mono.empty();
			}
		};
	}

	private static Map<String, String> mcpHeaders(String method) {
		Map<String, String> headers = new java.util.LinkedHashMap<>();
		headers.put("Accept", "application/json, text/event-stream");
		headers.put("Content-Type", "application/json");
		headers.put("MCP-Protocol-Version", V);
		headers.put("Mcp-Method", method);
		return headers;
	}

	private static McpHttpRequest post(String target, String body, Map<String, String> headers) {
		return request("POST", target, body, headers);
	}

	private static McpHttpRequest request(String method, String target, String body, Map<String, String> headers) {
		McpHttpHeaders.Builder values = McpHttpHeaders.builder();
		headers.forEach(values::add);
		McpHttpRequest.Builder builder = McpHttpRequest.builder()
			.method(method)
			.uri(URI.create(target.startsWith("/") ? "http://pipe" + target : target))
			.headers(values.build());
		if (body != null) {
			builder.body(body);
		}
		return builder.build();
	}

	private McpHttpResponse send(McpHttpRequest request) {
		return this.client.exchange().exchange(request, McpTransportContext.EMPTY).block(Duration.ofSeconds(5));
	}

	private static String read(McpHttpResponse response) {
		return JdkFlowAdapter.flowPublisherToFlux(response.body())
			.flatMapIterable(values -> values)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
			.collectList()
			.block(Duration.ofSeconds(5))
			.stream()
			.collect(Collectors.joining());
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

}
