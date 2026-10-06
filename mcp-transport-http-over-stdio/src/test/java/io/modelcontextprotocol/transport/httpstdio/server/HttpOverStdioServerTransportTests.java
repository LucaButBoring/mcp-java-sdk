package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.netty.channel.DefaultEventLoopGroup;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class HttpOverStdioServerTransportTests {

	/**
	 * Assembling the close pipeline must not start it, and running it must deliver GOAWAY
	 * before closing. Mono.fromFuture(future) used to fire every step at assembly.
	 */
	@Test
	void closeGracefullyIsLazyOnBothEndsAndDeliversGoAway() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		DefaultEventLoopGroup serverGroup = new DefaultEventLoopGroup();
		HttpOverStdioServerTransport server = HttpOverStdioServerTransport.start(pair[1], serverGroup, null,
				McpJsonDefaults.getMapper());
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
		try {
			Mono<Void> clientClose = client.closeGracefully();
			Mono<Void> serverClose = server.closeGracefully();
			Thread.sleep(200);
			assertThat(server.server().channel().isOpen()).as("server touched by assembly").isTrue();
			assertThat(server.server().goAwayReceived()).as("client GOAWAY sent by assembly").isNotDone();

			clientClose.block(Duration.ofSeconds(10));
			server.server().goAwayReceived().get(5, TimeUnit.SECONDS);
			serverClose.block(Duration.ofSeconds(10));
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(10));
			server.closeGracefully().block(Duration.ofSeconds(10));
			serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
		}
	}

	@Test
	void legacyHandlerRoundTripsJsonRpcRequest() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		DefaultEventLoopGroup serverGroup = new DefaultEventLoopGroup();
		HttpOverStdioServerTransport server = HttpOverStdioServerTransport.start(pair[1], serverGroup, null,
				McpJsonDefaults.getMapper());
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
		try {
			server.setMcpHandler(new McpStatelessServerHandler() {
				@Override
				public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
						McpSchema.JSONRPCRequest request) {
					return Mono.just(McpSchema.JSONRPCResponse.result(request.id(), "ok"));
				}

				@Override
				public Mono<Void> handleNotification(McpTransportContext context,
						McpSchema.JSONRPCNotification notification) {
					return Mono.empty();
				}
			});
			McpHttpRequest request = McpHttpRequest.builder()
				.method("POST")
				.uri(URI.create("http://pipe/mcp"))
				.headers(McpHttpHeaders.builder().add("accept", "application/json, text/event-stream").build())
				.body("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}")
				.build();

			var response = client.exchange().exchange(request, McpTransportContext.EMPTY).block(Duration.ofSeconds(5));
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(read(response.body())).contains("\"id\":\"1\"").contains("\"result\":\"ok\"");
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(10));
			server.closeGracefully().block(Duration.ofSeconds(10));
			serverGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
	}

	@Test
	void sseResponseUsesServletWireFormatAndClientCancelDisposesTheFlux() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(4096);
		DefaultEventLoopGroup serverGroup = new DefaultEventLoopGroup();
		java.util.concurrent.CountDownLatch disposed = new java.util.concurrent.CountDownLatch(1);
		reactor.core.publisher.Sinks.Many<io.modelcontextprotocol.server.transport.http.SseEvent> live = reactor.core.publisher.Sinks
			.many()
			.unicast()
			.onBackpressureBuffer();
		io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server server = io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server
			.start(pair[1], serverGroup, null, (request, writer) -> {
				reactor.core.publisher.Flux<io.modelcontextprotocol.server.transport.http.SseEvent> events = "/finite"
					.equals(request.path())
							? reactor.core.publisher.Flux.just(
									new io.modelcontextprotocol.server.transport.http.SseEvent(
											java.util.Optional.of("e1"), "message", "{\"a\":1}"),
									new io.modelcontextprotocol.server.transport.http.SseEvent(
											java.util.Optional.empty(), "message", "{\"b\":2}"))
							: live.asFlux().doOnCancel(disposed::countDown);
				HttpOverStdioServerTransport.writeResponse(writer,
						new io.modelcontextprotocol.server.transport.http.StatelessHttpResponse(200, Map.of(),
								new io.modelcontextprotocol.server.transport.http.StatelessHttpResponse.Sse(events)));
			});
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport.connect(pair[0]).get(5, TimeUnit.SECONDS);
		try {
			var finite = client.exchange()
				.exchange(get("/finite"), McpTransportContext.EMPTY)
				.block(Duration.ofSeconds(5));
			assertThat(finite.headers().firstValue("content-type")).contains("text/event-stream");
			assertThat(read(finite.body()))
				.isEqualTo("id: e1\nevent: message\ndata: {\"a\":1}\n\nevent: message\ndata: {\"b\":2}\n\n");

			var open = client.exchange().exchange(get("/live"), McpTransportContext.EMPTY).block(Duration.ofSeconds(5));
			java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
			reactor.core.Disposable reading = JdkFlowAdapter.flowPublisherToFlux(open.body())
				.subscribe(chunk -> first.countDown());
			live.tryEmitNext(new io.modelcontextprotocol.server.transport.http.SseEvent(java.util.Optional.empty(),
					"message", "{}"));
			assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
			reading.dispose();
			assertThat(disposed.await(5, TimeUnit.SECONDS)).as("client RST_STREAM must cancel the server Flux")
				.isTrue();
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(10));
			server.close().get(5, TimeUnit.SECONDS);
			serverGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
	}

	private static McpHttpRequest get(String path) {
		return McpHttpRequest.builder()
			.method("GET")
			.uri(URI.create("http://pipe" + path))
			.headers(McpHttpHeaders.builder().build())
			.build();
	}

	private static String read(java.util.concurrent.Flow.Publisher<List<ByteBuffer>> body) {
		return JdkFlowAdapter.flowPublisherToFlux(body)
			.flatMapIterable(values -> values)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
			.collectList()
			.block(Duration.ofSeconds(5))
			.stream()
			.collect(java.util.stream.Collectors.joining());
	}

}
