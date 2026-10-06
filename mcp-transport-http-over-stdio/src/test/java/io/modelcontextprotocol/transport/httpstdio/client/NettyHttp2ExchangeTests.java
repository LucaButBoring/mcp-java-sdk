package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Client;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class NettyHttp2ExchangeTests {

	private final List<DefaultEventLoopGroup> groups = new CopyOnWriteArrayList<>();

	private final List<PipeHttp2Client> clients = new CopyOnWriteArrayList<>();

	private final List<PipeHttp2Server> servers = new CopyOnWriteArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (PipeHttp2Client client : this.clients) {
			client.close().get(5, TimeUnit.SECONDS);
		}
		for (PipeHttp2Server server : this.servers) {
			server.close().get(5, TimeUnit.SECONDS);
		}
		for (DefaultEventLoopGroup group : this.groups) {
			group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
	}

	@Test
	void jsonResponsePreservesResponseMetadata() throws Exception {
		Fixture fixture = fixture((request, response) -> {
			response.headers("200", new DefaultHttp2Headers().add("content-type", "application/json"));
			response.data(Unpooled.copiedBuffer("{\"ok\":true}", StandardCharsets.UTF_8), true);
		});
		McpHttpRequest request = request("POST", "/mcp", "{\"id\":1}");

		var response = fixture.exchange.exchange(request, io.modelcontextprotocol.common.McpTransportContext.EMPTY)
			.block(java.time.Duration.ofSeconds(5));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type")).contains("application/json");
		assertThat(response.sentRequest()).isSameAs(request);
		assertThat(response.protocolVersion()).contains("HTTP/2");
		assertThat(read(response.body())).isEqualTo("{\"ok\":true}");
	}

	@Test
	void multiFrameSseBodyIsDeliveredInOrder() throws Exception {
		Fixture fixture = fixture((request, response) -> {
			response.headers("200", new DefaultHttp2Headers().add("content-type", "text/event-stream"));
			response.data(Unpooled.copiedBuffer("event: message\n", StandardCharsets.UTF_8), false);
			response.data(Unpooled.copiedBuffer("data: one\n\n", StandardCharsets.UTF_8), true);
		});
		var response = fixture.exchange
			.exchange(request("GET", "/events", null), io.modelcontextprotocol.common.McpTransportContext.EMPTY)
			.block(java.time.Duration.ofSeconds(5));
		assertThat(read(response.body())).isEqualTo("event: message\ndata: one\n\n");
	}

	@Test
	void cancellingBodyResetsOnlyThatStreamAndConnectionRemainsUsable() throws Exception {
		AtomicInteger cancellations = new AtomicInteger();
		Fixture fixture = fixture((request, response) -> {
			if ("/stream".equals(request.path())) {
				response.headers("200", new DefaultHttp2Headers().add("content-type", "text/event-stream"));
				response.onCancelled(cancellations::incrementAndGet);
			}
			else {
				response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
			}
		});
		var streaming = fixture.exchange
			.exchange(request("GET", "/stream", null), io.modelcontextprotocol.common.McpTransportContext.EMPTY)
			.block(java.time.Duration.ofSeconds(5));
		JdkFlowAdapter.flowPublisherToFlux(streaming.body()).subscribe().dispose();
		await(() -> cancellations.get() == 1);
		var later = fixture.exchange
			.exchange(request("GET", "/later", null), io.modelcontextprotocol.common.McpTransportContext.EMPTY)
			.block(java.time.Duration.ofSeconds(5));
		assertThat(read(later.body())).isEqualTo("ok");
		assertThat(cancellations).hasValue(1);
	}

	@Test
	void logicalUrlBecomesSchemeAuthorityAndPathPseudoHeaders() throws Exception {
		java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.http2.Http2Headers> seen = new java.util.concurrent.atomic.AtomicReference<>();
		Fixture fixture = fixture((request, response) -> {
			seen.set(request.headers());
			response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
		});
		McpHttpRequest request = McpHttpRequest.builder()
			.method("POST")
			.uri(URI.create("https://Child.Example:8443/mcp/v1?tenant=a%20b"))
			.headers(McpHttpHeaders.builder().add("Authorization", "Bearer t").build())
			.body("{}")
			.build();
		var response = fixture.exchange.exchange(request, io.modelcontextprotocol.common.McpTransportContext.EMPTY)
			.block(java.time.Duration.ofSeconds(5));
		assertThat(read(response.body())).isEqualTo("ok");
		assertThat(seen.get().scheme()).hasToString("https");
		assertThat(seen.get().authority()).hasToString("Child.Example:8443");
		assertThat(seen.get().path()).hasToString("/mcp/v1?tenant=a%20b");
		assertThat(seen.get().get("authorization")).hasToString("Bearer t");
	}

	@Test
	void resetBeforeHeadersFailsTheResponseInsteadOfHanging() throws Exception {
		Fixture fixture = fixture((request, response) -> {
			if ("/refuse".equals(request.path())) {
				response.reset(io.netty.handler.codec.http2.Http2Error.REFUSED_STREAM.code());
			}
			else {
				response.headers("200", new DefaultHttp2Headers());
				response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
			}
		});
		Mono<?> exchange = fixture.exchange.exchange(request("POST", "/refuse", "{}"),
				io.modelcontextprotocol.common.McpTransportContext.EMPTY);
		// Phase 0 reports a peer reset either as the reset itself or as the stream
		// closing
		// before end-of-stream, depending on which event the stream channel sees first.
		// Either is a prompt terminal failure; the old exchange hung until the timeout.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> exchange.block(java.time.Duration.ofSeconds(5)))
			.isInstanceOfAny(io.modelcontextprotocol.transport.httpstdio.http2.Http2StreamResetException.class,
					io.modelcontextprotocol.transport.httpstdio.http2.Http2TransportException.class);
		var later = fixture.exchange
			.exchange(request("GET", "/later", null), io.modelcontextprotocol.common.McpTransportContext.EMPTY)
			.block(java.time.Duration.ofSeconds(5));
		assertThat(later.statusCode()).isEqualTo(200);
	}

	private Fixture fixture(io.modelcontextprotocol.transport.httpstdio.http2.Http2RequestHandler handler)
			throws Exception {
		DuplexByteChannel[] duplex = InMemoryDuplexByteChannel.pair(4096);
		DefaultEventLoopGroup clientGroup = group("exchange-client");
		DefaultEventLoopGroup serverGroup = group("exchange-server");
		PipeHttp2Server server = PipeHttp2Server.start(duplex[1], serverGroup, null, handler);
		PipeHttp2Client client = PipeHttp2Client.connect(duplex[0], clientGroup, null).get(5, TimeUnit.SECONDS);
		this.clients.add(client);
		this.servers.add(server);
		return new Fixture(new NettyHttp2Exchange(client));
	}

	private DefaultEventLoopGroup group(String name) {
		DefaultEventLoopGroup group = new DefaultEventLoopGroup(2, new DefaultThreadFactory(name));
		this.groups.add(group);
		return group;
	}

	private static McpHttpRequest request(String method, String path, String body) {
		McpHttpRequest.Builder builder = McpHttpRequest.builder()
			.method(method)
			.uri(URI.create("http://pipe" + path))
			.headers(McpHttpHeaders.builder().add("X-Test", "yes").build());
		if (body != null) {
			builder.body(body);
		}
		return builder.build();
	}

	private static String read(java.util.concurrent.Flow.Publisher<List<ByteBuffer>> publisher) {
		return JdkFlowAdapter.flowPublisherToFlux(publisher)
			.flatMapIterable(values -> values)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
			.collectList()
			.block(java.time.Duration.ofSeconds(5))
			.stream()
			.collect(java.util.stream.Collectors.joining());
	}

	private static void await(CheckedBoolean condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(condition.get()).isTrue();
	}

	private record Fixture(NettyHttp2Exchange exchange) {
	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

}
