package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import io.modelcontextprotocol.transport.httpstdio.client.PipeRequests;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.netty.channel.DefaultEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transport contract for the MCP 2026-07-28 Streamable HTTP shape over the stdio pipe,
 * driven through the real client exchange. Behaviors requiring SDK-core 2026 features
 * (client header computation, server-produced progress and {@code subscriptions/listen}
 * semantics) are deferred as Tracks B and C; the streaming cases here use a synthetic
 * {@link McpStatelessStreamingServerHandler} to pin what the transport must carry.
 */
@Timeout(60)
class HttpOverStdioContractTests {

	private static final String V = "2026-07-28";

	private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

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

	// ---------------------------------------------------------------- concurrency

	@Test
	void concurrentRequestsCompleteIndependentlyAndOutOfOrder() throws Exception {
		connect(builder -> {
		});
		// No handler answers until all 32 requests are in flight, so a transport that
		// serialized requests would deadlock here instead of passing.
		CountDownLatch arrived = new CountDownLatch(32);
		reactor.core.publisher.Sinks.Empty<Void> allArrived = reactor.core.publisher.Sinks.empty();
		this.server.setStreamingHandler(exchange -> {
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) exchange.message();
			int n = Integer.parseInt(request.id().toString());
			arrived.countDown();
			if (arrived.getCount() == 0) {
				allArrived.tryEmitEmpty();
			}
			// Once released, later requests finish first.
			return allArrived.asMono()
				.then(Mono.delay(Duration.ofMillis(10L * (32 - n))))
				.thenReturn(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(request.id(), n)));
		});
		List<Integer> completionOrder = new CopyOnWriteArrayList<>();
		List<String> bodies = Flux.range(0, 32)
			.flatMap(n -> exchange(post(request(Integer.toString(n), "tools/list", Map.of()), headers("tools/list")))
				.flatMap(response -> body(response))
				.doOnNext(body -> completionOrder.add(n)), 32)
			.collectList()
			.block(Duration.ofSeconds(30));
		assertThat(arrived.getCount()).isZero();
		assertThat(bodies).hasSize(32);
		for (int n = 0; n < 32; n++) {
			String id = "\"id\":\"" + n + "\"";
			assertThat(bodies).filteredOn(body -> body.contains(id))
				.singleElement()
				.asString()
				.contains("\"result\":" + n);
		}
		assertThat(completionOrder.indexOf(31)).as("the last-sent request completes before the first")
			.isLessThan(completionOrder.indexOf(0));
	}

	@Test
	void concurrentSseResponsesCarryOnlyTheirOwnNotificationsInOrder() throws Exception {
		connect(builder -> {
		});
		// Every stream waits until all eight are subscribed, so they are open together.
		CountDownLatch subscribed = new CountDownLatch(8);
		reactor.core.publisher.Sinks.Empty<Void> allOpen = reactor.core.publisher.Sinks.empty();
		this.server.setStreamingHandler(exchange -> {
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) exchange.message();
			Flux<McpSchema.JSONRPCMessage> progress = Flux.interval(Duration.ofMillis(3))
				.take(5)
				.map(i -> new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "notifications/progress",
						Map.of("progressToken", request.id(), "progress", i)));
			Flux<McpSchema.JSONRPCMessage> gated = allOpen.asMono()
				.thenMany(progress.cast(McpSchema.JSONRPCMessage.class)
					.concatWithValues(McpSchema.JSONRPCResponse.result(request.id(), "done-" + request.id())))
				.doOnSubscribe(subscription -> {
					subscribed.countDown();
					if (subscribed.getCount() == 0) {
						allOpen.tryEmitEmpty();
					}
				});
			return Mono.just(McpStatelessServerResult.Stream.requestScoped(gated));
		});
		List<String> bodies = Flux.range(0, 8)
			.flatMap(n -> exchange(post(request("s" + n, "tools/call", Map.of("name", "t")), callHeaders("t")))
				.flatMap(response -> {
					assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
					assertThat(response.headers().firstValue("x-accel-buffering")).contains("no");
					return body(response);
				}), 8)
			.collectList()
			.block(Duration.ofSeconds(30));
		for (int n = 0; n < 8; n++) {
			String token = "s" + n;
			String body = bodies.stream()
				.filter(candidate -> candidate.contains("done-" + token))
				.findFirst()
				.orElseThrow();
			List<String> data = dataLines(body);
			assertThat(data).hasSize(6);
			for (int i = 0; i < 5; i++) {
				assertThat(data.get(i)).contains("\"progressToken\":\"" + token + "\"").contains("\"progress\":" + i);
			}
			assertThat(data.get(5)).contains("\"id\":\"" + token + "\"").contains("done-" + token);
		}
	}

	@Test
	void cancellingOneStreamLeavesSiblingsAndTheConnectionIntact() throws Exception {
		connect(builder -> {
		});
		CountDownLatch cancelled = new CountDownLatch(1);
		this.server.setStreamingHandler(exchange -> {
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) exchange.message();
			if ("victim".equals(request.id())) {
				return Mono.just(McpStatelessServerResult.Stream
					.requestScoped(Flux.<McpSchema.JSONRPCMessage>just(progress(request.id(), 0))
						.concatWith(Flux.never())
						.doOnCancel(cancelled::countDown)));
			}
			return Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.interval(Duration.ofMillis(20))
				.take(10).<McpSchema
						.JSONRPCMessage>map(i -> progress(request.id(), i))
				.concatWithValues(McpSchema.JSONRPCResponse.result(request.id(), "ok"))));
		});
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> victim = exchange(
				post(request("victim", "tools/call", Map.of("name", "t")), callHeaders("t")))
			.block(Duration.ofSeconds(5));
		Mono<String> sibling = exchange(post(request("sibling", "tools/call", Map.of("name", "t")), callHeaders("t")))
			.flatMap(HttpOverStdioContractTests::body)
			.cache();
		sibling.subscribe();
		CountDownLatch firstChunk = new CountDownLatch(1);
		Disposable reading = JdkFlowAdapter.flowPublisherToFlux(victim.body())
			.subscribe(chunk -> firstChunk.countDown());
		assertThat(firstChunk.await(5, TimeUnit.SECONDS)).isTrue();
		reading.dispose();

		assertThat(cancelled.await(5, TimeUnit.SECONDS)).as("RST_STREAM cancels the handler stream").isTrue();
		assertThat(dataLines(sibling.block(Duration.ofSeconds(10)))).hasSize(11);
		assertThat(statusOf(post(request("after", "tools/call", Map.of("name", "t")), callHeaders("t"))))
			.isEqualTo(200);
	}

	@Test
	void longLivedListenStreamStaysOpenAlongsideOtherRequestsAndKeepsAlive() throws Exception {
		connect(builder -> builder.keepAliveInterval(Duration.ofMillis(100)));
		CountDownLatch cancelled = new CountDownLatch(1);
		this.server.setStreamingHandler(exchange -> {
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) exchange.message();
			if ("subscriptions/listen".equals(request.method())) {
				McpSchema.JSONRPCMessage ack = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
						"notifications/subscriptions/acknowledged",
						Map.of("_meta", Map.of("io.modelcontextprotocol/subscriptionId", request.id())));
				return Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(ack)
					.concatWith(Flux.<McpSchema.JSONRPCMessage>never().doOnCancel(cancelled::countDown))));
			}
			return Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(request.id(), "ok")));
		});
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> listen = exchange(
				post(request("L1", "subscriptions/listen", Map.of()), headers("subscriptions/listen")))
			.block(Duration.ofSeconds(5));
		assertThat(listen.headers().firstValue("content-type")).contains("text/event-stream");
		// Body chunks are not SSE frame boundaries: accumulate and parse lines.
		StringBuilder received = new StringBuilder();
		CountDownLatch keepAlive = new CountDownLatch(1);
		Disposable reading = text(listen).subscribe(chunk -> {
			synchronized (received) {
				received.append(chunk);
				List<String> lines = List.of(received.toString().split("\n", -1));
				int ack = indexOfLineContaining(lines, "notifications/subscriptions/acknowledged");
				if (ack >= 0 && lines.subList(ack + 1, lines.size()).contains(":")) {
					keepAlive.countDown();
				}
			}
		});
		assertThat(keepAlive.await(5, TimeUnit.SECONDS)).as("a ':' comment line after the acknowledgment").isTrue();
		synchronized (received) {
			List<String> data = dataLines(received.toString());
			assertThat(data.get(0)).as("acknowledgment first")
				.contains("notifications/subscriptions/acknowledged")
				.contains("\"io.modelcontextprotocol/subscriptionId\":\"L1\"");
		}

		for (int i = 0; i < 5; i++) {
			assertThat(statusOf(post(request("q" + i, "tools/list", Map.of()), headers("tools/list")))).isEqualTo(200);
		}
		reading.dispose();
		assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(statusOf(post(request("q-after", "tools/list", Map.of()), headers("tools/list")))).isEqualTo(200);
	}

	// ----------------------------------------------------------- HTTP semantics

	@Test
	void acceptedNotificationAnswers202WithNoBody() throws Exception {
		connect(builder -> {
		});
		CountDownLatch seen = new CountDownLatch(1);
		this.server.setStreamingHandler(exchange -> {
			seen.countDown();
			return Mono.just(new McpStatelessServerResult.Accepted());
		});
		Map<String, String> headers = Map.of("Accept", "application/json, text/event-stream");
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = exchange(
				post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", headers))
			.block(Duration.ofSeconds(5));
		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(body(response).block(Duration.ofSeconds(5))).isEmpty();
		assertThat(seen.getCount()).isZero();
	}

	@Test
	void missingAcceptIsRejected() throws Exception {
		connect(builder -> {
		});
		this.server.setStreamingHandler(exchange -> Mono.error(new AssertionError("handler must not run")));
		Map<String, String> headers = new LinkedHashMap<>(headers("tools/list"));
		headers.remove("Accept");
		assertThat(statusOf(post(request("1", "tools/list", Map.of()), headers))).isEqualTo(400);
	}

	@Test
	void legacySessionAndReplayHeadersAreIgnored() throws Exception {
		connect(builder -> {
		});
		this.server.setStreamingHandler(exchange -> Mono.just(new McpStatelessServerResult.Single(
				McpSchema.JSONRPCResponse.result(((McpSchema.JSONRPCRequest) exchange.message()).id(), "ok"))));
		Map<String, String> headers = new LinkedHashMap<>(headers("tools/list"));
		headers.put("Mcp-Session-Id", "stale-session");
		headers.put("Last-Event-ID", "42");
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = exchange(
				post(request("1", "tools/list", Map.of()), headers))
			.block(Duration.ofSeconds(5));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("mcp-session-id")).as("no session minted or echoed").isEmpty();
	}

	@Test
	void oversizedRequestFailsOnlyThatStream() throws Exception {
		connect(builder -> builder.requestMaxSize(1024));
		this.server.setStreamingHandler(exchange -> Mono.just(new McpStatelessServerResult.Single(
				McpSchema.JSONRPCResponse.result(((McpSchema.JSONRPCRequest) exchange.message()).id(), "ok"))));
		String huge = request("big", "tools/call", Map.of("name", "t", "arguments", Map.of("x", "y".repeat(4096))));
		Mono<Integer> oversized = exchange(post(huge, callHeaders("t"))).map(HttpResponse::statusCode)
			.onErrorReturn(-1);
		assertThat(oversized.block(Duration.ofSeconds(10))).as("reset or 413, never accepted").isIn(-1, 413);
		assertThat(statusOf(post(request("small", "tools/list", Map.of()), headers("tools/list")))).isEqualTo(200);
	}

	// ------------------------------------------------------------------ framing

	@Test
	void malformedFramesCloseTheServerConnection() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		this.serverGroup = new DefaultEventLoopGroup();
		this.server = HttpOverStdioServerTransport.builder(pair[1]).eventLoopGroup(this.serverGroup).build();
		var out = pair[0].outbound();
		out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		// A HEADERS frame on stream 0, which RFC 9113 forbids, as the first frame.
		out.write(new byte[] { 0, 0, 0, 1, 4, 0, 0, 0, 0 });
		out.flush();
		this.server.server().closed().get(10, TimeUnit.SECONDS);
		assertThat(this.server.server().channel().isOpen()).isFalse();
		pair[0].close();
	}

	// ------------------------------------------------------------------ helpers

	private void connect(Consumer<HttpOverStdioServerTransport.Builder> customizer) throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(64 * 1024);
		this.serverGroup = new DefaultEventLoopGroup();
		HttpOverStdioServerTransport.Builder builder = HttpOverStdioServerTransport.builder(pair[1])
			.eventLoopGroup(this.serverGroup);
		customizer.accept(builder);
		this.server = builder.build();
		this.client = HttpOverStdioClientTransport.connect(pair[0]).get(10, TimeUnit.SECONDS);
	}

	private Mono<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> exchange(HttpRequest request) {
		return PipeRequests.send(this.client.httpClient(), request);
	}

	private int statusOf(HttpRequest request) {
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = exchange(request).block(Duration.ofSeconds(10));
		body(response).block(Duration.ofSeconds(10));
		return response.statusCode();
	}

	private static Flux<String> text(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return PipeRequests.text(response);
	}

	private static Mono<String> body(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return PipeRequests.body(response);
	}

	private static int indexOfLineContaining(List<String> lines, String fragment) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains(fragment)) {
				return i;
			}
		}
		return -1;
	}

	private static List<String> dataLines(String sse) {
		List<String> data = new ArrayList<>();
		for (String line : sse.split("\n")) {
			if (line.startsWith("data: ")) {
				data.add(line.substring("data: ".length()));
			}
		}
		return data;
	}

	private static McpSchema.JSONRPCNotification progress(Object token, long value) {
		return new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "notifications/progress",
				Map.of("progressToken", token, "progress", value));
	}

	private static String request(String id, String method, Map<String, Object> params) {
		return request(id, method, params, V);
	}

	private static String request(String id, String method, Map<String, Object> params, String version) {
		Map<String, Object> withMeta = new LinkedHashMap<>(params);
		withMeta.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", version));
		try {
			return JSON
				.writeValueAsString(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, id, withMeta));
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Map<String, String> headers(String method) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Accept", "application/json, text/event-stream");
		headers.put("Content-Type", "application/json");
		headers.put("MCP-Protocol-Version", V);
		headers.put("Mcp-Method", method);
		return headers;
	}

	private static Map<String, String> callHeaders(String tool) {
		Map<String, String> headers = headers("tools/call");
		headers.put("Mcp-Name", tool);
		return headers;
	}

	private static HttpRequest post(String body, Map<String, String> headers) {
		return PipeRequests.request("POST", "http://pipe/mcp", headers, body);
	}

}
