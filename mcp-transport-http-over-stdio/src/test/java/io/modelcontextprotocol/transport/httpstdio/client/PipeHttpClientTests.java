package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.net.http.HttpRequest;
import java.util.Map;

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

class PipeHttpClientTests {

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
		HttpRequest request = request("POST", "/mcp", "{\"id\":1}");

		var response = PipeRequests.sendNow(fixture.client, request);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type")).contains("application/json");
		assertThat(response.request()).isSameAs(request);
		assertThat(response.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_2);
		assertThat(PipeRequests.read(response)).isEqualTo("{\"ok\":true}");
	}

	@Test
	void multiFrameSseBodyIsDeliveredInOrder() throws Exception {
		Fixture fixture = fixture((request, response) -> {
			response.headers("200", new DefaultHttp2Headers().add("content-type", "text/event-stream"));
			response.data(Unpooled.copiedBuffer("event: message\n", StandardCharsets.UTF_8), false);
			response.data(Unpooled.copiedBuffer("data: one\n\n", StandardCharsets.UTF_8), true);
		});
		var response = PipeRequests.sendNow(fixture.client, request("GET", "/events", null));
		assertThat(PipeRequests.read(response)).isEqualTo("event: message\ndata: one\n\n");
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
		var streaming = PipeRequests.sendNow(fixture.client, request("GET", "/stream", null));
		JdkFlowAdapter.flowPublisherToFlux(streaming.body()).subscribe().dispose();
		await(() -> cancellations.get() == 1);
		var later = PipeRequests.sendNow(fixture.client, request("GET", "/later", null));
		assertThat(PipeRequests.read(later)).isEqualTo("ok");
		assertThat(cancellations).hasValue(1);
	}

	@Test
	void logicalUrlBecomesSchemeAuthorityAndPathPseudoHeaders() throws Exception {
		java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.http2.Http2Headers> seen = new java.util.concurrent.atomic.AtomicReference<>();
		Fixture fixture = fixture((request, response) -> {
			seen.set(request.headers());
			response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
		});
		HttpRequest request = PipeRequests.request("POST", "https://Child.Example:8443/mcp/v1?tenant=a%20b",
				Map.of("Authorization", "Bearer t"), "{}");
		var response = PipeRequests.sendNow(fixture.client, request);
		assertThat(PipeRequests.read(response)).isEqualTo("ok");
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
		Mono<?> exchange = PipeRequests.send(fixture.client, request("POST", "/refuse", "{}"));
		// Phase 0 reports a peer reset either as the reset itself or as the stream
		// closing
		// before end-of-stream, depending on which event the stream channel sees first.
		// Either is a prompt terminal failure; the old exchange hung until the timeout.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> exchange.block(java.time.Duration.ofSeconds(5)))
			.isInstanceOfAny(io.modelcontextprotocol.transport.httpstdio.http2.Http2StreamResetException.class,
					io.modelcontextprotocol.transport.httpstdio.http2.Http2TransportException.class);
		var later = PipeRequests.sendNow(fixture.client, request("GET", "/later", null));
		assertThat(later.statusCode()).isEqualTo(200);
	}

	@Test
	void multiBufferRequestBodyArrivesWholeAndConnectionHeadersAreNotForwarded() throws Exception {
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.http2.Http2Headers> seen = new java.util.concurrent.atomic.AtomicReference<>();
		Fixture fixture = fixture((request, response) -> {
			body.set(request.body().toString(StandardCharsets.UTF_8));
			seen.set(request.headers());
			response.headers("200", new DefaultHttp2Headers());
			response.end();
		});
		Flux<ByteBuffer> parts = Flux.just("{\"a\":", "1,", "\"b\":2}")
			.map(part -> ByteBuffer.wrap(part.getBytes(StandardCharsets.UTF_8)));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://pipe/mcp"))
			.header("Keep-Alive", "timeout=5")
			.header("X-Kept", "yes")
			.POST(HttpRequest.BodyPublishers.fromPublisher(JdkFlowAdapter.publisherToFlowPublisher(parts)))
			.build();
		assertThat(PipeRequests.sendNow(fixture.client, request).statusCode()).isEqualTo(200);
		assertThat(body.get()).isEqualTo("{\"a\":1,\"b\":2}");
		assertThat(seen.get().get("x-kept")).hasToString("yes");
		assertThat(seen.get().contains("keep-alive")).as("HTTP/2 forbids connection-specific fields").isFalse();
	}

	@Test
	void failingRequestBodyFailsTheFutureWithoutOpeningAStream() throws Exception {
		AtomicInteger streams = new AtomicInteger();
		Fixture fixture = fixture((request, response) -> {
			streams.incrementAndGet();
			response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
		});
		Flux<ByteBuffer> failing = Flux.concat(Flux.just(ByteBuffer.wrap(new byte[] { '{' })),
				Flux.error(new java.io.IOException("body source broke")));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://pipe/mcp"))
			.POST(HttpRequest.BodyPublishers.fromPublisher(JdkFlowAdapter.publisherToFlowPublisher(failing)))
			.build();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> PipeRequests.sendNow(fixture.client, request))
			.hasMessageContaining("body source broke");
		assertThat(PipeRequests.read(PipeRequests.sendNow(fixture.client, request("GET", "/later", null))))
			.isEqualTo("ok");
		assertThat(streams).as("only the later request opened a stream").hasValue(1);
	}

	@Test
	void cancellingWhileTheRequestBodyIsPendingStopsItAndOpensNoStream() throws Exception {
		AtomicInteger streams = new AtomicInteger();
		Fixture fixture = fixture((request, response) -> {
			streams.incrementAndGet();
			response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
		});
		java.util.concurrent.CountDownLatch bodyCancelled = new java.util.concurrent.CountDownLatch(1);
		Flux<ByteBuffer> never = Flux.<ByteBuffer>never().doOnCancel(bodyCancelled::countDown);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://pipe/mcp"))
			.POST(HttpRequest.BodyPublishers.fromPublisher(JdkFlowAdapter.publisherToFlowPublisher(never)))
			.build();
		var future = fixture.client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofPublisher());
		future.cancel(true);
		assertThat(bodyCancelled.await(5, TimeUnit.SECONDS)).as("the body subscription is cancelled").isTrue();
		assertThat(PipeRequests.read(PipeRequests.sendNow(fixture.client, request("GET", "/later", null))))
			.isEqualTo("ok");
		assertThat(streams).hasValue(1);
	}

	@Test
	void cancellingTheFutureBeforeHeadersResetsOnlyThatStream() throws Exception {
		AtomicInteger cancellations = new AtomicInteger();
		java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
		Fixture fixture = fixture((request, response) -> {
			if ("/slow".equals(request.path())) {
				response.onCancelled(cancellations::incrementAndGet);
				arrived.countDown();
			}
			else {
				response.data(Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true);
			}
		});
		var future = fixture.client.sendAsync(request("GET", "/slow", null),
				java.net.http.HttpResponse.BodyHandlers.ofPublisher());
		assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
		future.cancel(true);
		await(() -> cancellations.get() == 1);
		assertThat(PipeRequests.read(PipeRequests.sendNow(fixture.client, request("GET", "/later", null))))
			.isEqualTo("ok");
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
		return new Fixture(new PipeHttpClient(client));
	}

	private DefaultEventLoopGroup group(String name) {
		DefaultEventLoopGroup group = new DefaultEventLoopGroup(2, new DefaultThreadFactory(name));
		this.groups.add(group);
		return group;
	}

	private static HttpRequest request(String method, String path, String body) {
		return PipeRequests.request(method, "http://pipe" + path, Map.of("X-Test", "yes"), body);
	}

	private static void await(CheckedBoolean condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(condition.get()).isTrue();
	}

	private record Fixture(PipeHttpClient client) {
	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

}
