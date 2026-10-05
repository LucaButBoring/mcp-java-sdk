package io.modelcontextprotocol.transport.httpstdio.http2;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InboundEofEvent;
import io.modelcontextprotocol.transport.httpstdio.pipe.NettyPipeChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipeHttp2GateTests {

	private final List<NettyPipeChannel> channels = new CopyOnWriteArrayList<>();

	private final List<DefaultEventLoopGroup> groups = new CopyOnWriteArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (NettyPipeChannel channel : this.channels) {
			channel.close().await(5, TimeUnit.SECONDS);
		}
		for (DefaultEventLoopGroup group : this.groups) {
			group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
	}

	@Test
	void prefaceAndSettingsComplete() throws Exception {
		Fixture fixture = fixture(4096, (PipeChannelConfig) null, (AtomicInteger) null);
		assertThat(fixture.client.settingsReceived().get(5, TimeUnit.SECONDS)).isNull();
		assertThat(fixture.server.clientSettingsReceived().get(5, TimeUnit.SECONDS)).isNull();
		assertThat(fixture.client.channel().isActive()).isTrue();
	}

	@Test
	void concurrentStreamsRoundTrip() throws Exception {
		Fixture fixture = fixture(4096, (PipeChannelConfig) null, (AtomicInteger) null);
		List<byte[]> expected = new ArrayList<>();
		List<CompletableFuture<Http2Response>> requests = new ArrayList<>();
		for (int index = 0; index < 16; index++) {
			byte[] body = randomBytes(64 * 1024, 100 + index);
			expected.add(body);
			requests.add(fixture.client.request("POST", "/echo", null, Unpooled.wrappedBuffer(body)));
		}
		CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
		for (int index = 0; index < requests.size(); index++) {
			Http2Response response = requests.get(index).join();
			try {
				assertThat(response.status()).isEqualTo("200");
				assertThat(bytes(response.body())).isEqualTo(expected.get(index));
			}
			finally {
				response.body().release();
			}
		}
		assertThat(this.channels).containsExactlyInAnyOrder(fixture.client.channel(), fixture.server.channel());
	}

	@Test
	void flowControlPressureNoDeadlock() throws Exception {
		PipeChannelConfig clientConfig = config(4096, 16 * 1024, 16 * 1024, true);
		PipeChannelConfig serverConfig = config(4096, 16 * 1024, 16 * 1024, true);
		Fixture fixture = fixture(4096, clientConfig, serverConfig);
		byte[] expected = randomBytes(4 * 1024 * 1024, 777);
		ByteArrayOutputStream actual = new ByteArrayOutputStream(expected.length);
		CompletableFuture<Void> complete = new CompletableFuture<>();
		ConcurrentLinkedQueue<PendingData> pending = new ConcurrentLinkedQueue<>();
		AtomicBoolean drainScheduled = new AtomicBoolean();
		StreamingResponse response = fixture.client.requestStreaming("POST", "/echo", null,
				Unpooled.wrappedBuffer(expected), new StreamingResponse.Listener() {
					@Override
					public void onData(ByteBuf data, boolean end) {
						pending.add(new PendingData(data, end));
						schedulePressureDrain(fixture.client, pending, drainScheduled, actual, complete);
					}

					@Override
					public void onReset(long error) {
						complete.completeExceptionally(new AssertionError("reset=" + error));
					}
				});
		complete.get(60, TimeUnit.SECONDS);
		assertThat(actual.toByteArray()).isEqualTo(expected);
		assertThat(fixture.client.channel().metrics().peakInboundQueuedBytes()).isLessThanOrEqualTo(20 * 1024);
		assertThat(fixture.client.channel().metrics().peakOutboundQueuedBytes()).isLessThanOrEqualTo(20 * 1024);
		System.out.printf("FLOW_METRICS inboundPeak=%d outboundPeak=%d readerBlockedNanos=%d writerBlockedNanos=%d%n",
				fixture.client.channel().metrics().peakInboundQueuedBytes(),
				fixture.client.channel().metrics().peakOutboundQueuedBytes(),
				fixture.client.channel().metrics().readerBlockedNanos(),
				fixture.client.channel().metrics().writerBlockedNanos());
		assertThat(pending).isEmpty();
	}

	@Test
	void rstStreamIsolation() throws Exception {
		AtomicInteger cancelled = new AtomicInteger();
		Fixture fixture = fixture(4096, null, cancelled);
		assertPing(fixture.client);
		CountDownLatch frames = new CountDownLatch(5);
		StreamingResponse slow = fixture.client.requestStreaming("GET", "/slow", null, Unpooled.EMPTY_BUFFER,
				new StreamingResponse.Listener() {
					@Override
					public void onData(ByteBuf data, boolean end) {
						data.release();
						frames.countDown();
					}
				});
		CompletableFuture<Http2Response> during = fixture.client.request("POST", "/echo", null,
				Unpooled.wrappedBuffer(randomBytes(64 * 1024, 9)));
		assertThat(frames.await(5, TimeUnit.SECONDS)).isTrue();
		slow.cancel().get(5, TimeUnit.SECONDS);
		Http2Response echoed = during.get(10, TimeUnit.SECONDS);
		echoed.body().release();
		awaitCondition(() -> cancelled.get() == 1, Duration.ofSeconds(5));
		assertPing(fixture.client);
		assertThat(fixture.client.channel().isActive()).isTrue();
		assertThat(fixture.server.channel().isActive()).isTrue();
	}

	@Test
	void directionalEof() throws Exception {
		Fixture fixture = fixture(4096, null, null, false);
		CountDownLatch eof = new CountDownLatch(1);
		fixture.server.channel().pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void userEventTriggered(ChannelHandlerContext context, Object event) {
				if (event == InboundEofEvent.INSTANCE) {
					eof.countDown();
				}
				context.fireUserEventTriggered(event);
			}
		});
		fixture.client.channel().shutdownOutput().sync();
		assertThat(eof.await(5, TimeUnit.SECONDS)).isTrue();
		fixture.server.goAway().get(5, TimeUnit.SECONDS);
		assertThat(fixture.client.goAwayReceived().get(5, TimeUnit.SECONDS)).isNull();
		assertThat(fixture.client.channel().isOpen()).isTrue();
	}

	@Test
	void goAwayExchange() throws Exception {
		Fixture fixture = fixture(4096, (PipeChannelConfig) null, (AtomicInteger) null);
		CompletableFuture<Http2Response> existing = fixture.client.request("POST", "/echo", null,
				Unpooled.wrappedBuffer(randomBytes(256 * 1024, 13)));
		fixture.client.goAway().get(5, TimeUnit.SECONDS);
		assertThat(fixture.server.goAwayReceived().get(5, TimeUnit.SECONDS)).isNull();
		Http2Response response = existing.get(15, TimeUnit.SECONDS);
		response.body().release();
		assertThatThrownBy(
				() -> fixture.client.request("GET", "/ping", null, Unpooled.EMPTY_BUFFER).get(5, TimeUnit.SECONDS))
			.hasCauseInstanceOf(Exception.class);
	}

	@Test
	void cleanShutdownNoBlockedThreads() throws Exception {
		Fixture fixture = fixture(4096, (PipeChannelConfig) null, (AtomicInteger) null);
		fixture.client.close().get(5, TimeUnit.SECONDS);
		fixture.server.close().get(5, TimeUnit.SECONDS);
		for (DefaultEventLoopGroup group : this.groups) {
			group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
		awaitCondition(PipeHttp2GateTests::noPipeThreads, Duration.ofSeconds(5));
		assertThat(blockedPipeIoThreads()).isEmpty();
	}

	private Fixture fixture(int pipeBytes, PipeChannelConfig clientConfig, AtomicInteger cancelled) throws Exception {
		return fixture(pipeBytes, clientConfig, cancelled, true);
	}

	private Fixture fixture(int pipeBytes, PipeChannelConfig clientConfig, AtomicInteger cancelled,
			boolean closeServerOnEof) throws Exception {
		PipeChannelConfig serverConfig = config(16 * 1024, 256 * 1024, 256 * 1024, closeServerOnEof);
		return fixture(pipeBytes, clientConfig, serverConfig, cancelled);
	}

	private Fixture fixture(int pipeBytes, PipeChannelConfig clientConfig, PipeChannelConfig serverConfig)
			throws Exception {
		return fixture(pipeBytes, clientConfig, serverConfig, null);
	}

	private Fixture fixture(int pipeBytes, PipeChannelConfig clientConfig, PipeChannelConfig serverConfig,
			AtomicInteger cancelled) throws Exception {
		DuplexByteChannel[] duplex = InMemoryDuplexByteChannel.pair(pipeBytes);
		DefaultEventLoopGroup clientGroup = group("http2-client");
		DefaultEventLoopGroup serverGroup = group("http2-server");
		PipeHttp2Server[] holder = new PipeHttp2Server[1];
		PipeHttp2Server server = PipeHttp2Server.start(duplex[1], serverGroup, serverConfig,
				(request, response) -> handle(holder[0], request, response, cancelled));
		holder[0] = server;
		PipeHttp2Client client = PipeHttp2Client.connect(duplex[0], clientGroup, clientConfig).get(5, TimeUnit.SECONDS);
		this.channels.add(client.channel());
		this.channels.add(server.channel());
		return new Fixture(client, server);
	}

	private static void handle(PipeHttp2Server server, Http2Request request, Http2ResponseWriter response,
			AtomicInteger cancelled) {
		if ("POST".equals(request.method()) && "/echo".equals(request.path())) {
			ByteBuf retainedBody = request.body().retainedDuplicate();
			server.channel().eventLoop().execute(() -> {
				try {
					response.headers("200", new DefaultHttp2Headers().add("x-echo-stream", "1"));
					while (retainedBody.isReadable()) {
						int count = Math.min(1024, retainedBody.readableBytes());
						response.data(retainedBody.readRetainedSlice(count), !retainedBody.isReadable());
					}
					if (retainedBody.capacity() == 0) {
						response.end();
					}
				}
				finally {
					retainedBody.release();
				}
			});
			return;
		}
		if ("GET".equals(request.method()) && "/ping".equals(request.path())) {
			response.headers("200", null);
			response.data(Unpooled.wrappedBuffer(new byte[] { 'p', 'o', 'n', 'g' }), true);
			return;
		}
		if ("GET".equals(request.method()) && "/slow".equals(request.path())) {
			response.headers("200", null);
			AtomicBoolean stopped = new AtomicBoolean();
			response.onCancelled(() -> {
				if (stopped.compareAndSet(false, true) && cancelled != null) {
					cancelled.incrementAndGet();
				}
			});
			emitSlow(server, response, stopped);
			return;
		}
		response.headers("404", null);
		response.end();
	}

	private static void emitSlow(PipeHttp2Server server, Http2ResponseWriter response, AtomicBoolean stopped) {
		server.channel().eventLoop().schedule(() -> {
			if (!stopped.get() && server.channel().isActive()) {
				response.data(Unpooled.buffer(1024).writeZero(1024), false);
				emitSlow(server, response, stopped);
			}
		}, 20, TimeUnit.MILLISECONDS);
	}

	private void assertPing(PipeHttp2Client client) throws Exception {
		Http2Response response = client.request("GET", "/ping", null, Unpooled.EMPTY_BUFFER).get(5, TimeUnit.SECONDS);
		try {
			assertThat(response.status()).isEqualTo("200");
			assertThat(bytes(response.body())).isEqualTo(new byte[] { 'p', 'o', 'n', 'g' });
		}
		finally {
			response.body().release();
		}
	}

	private DefaultEventLoopGroup group(String name) {
		DefaultEventLoopGroup group = new DefaultEventLoopGroup(2, new DefaultThreadFactory(name));
		this.groups.add(group);
		return group;
	}

	private static PipeChannelConfig config(int chunk, int inbound, int outbound, boolean closeOnEof) {
		return new PipeChannelConfig(new EmbeddedChannel()).setInboundReadChunkBytes(chunk)
			.setMaxInboundQueuedBytes(inbound)
			.setMaxOutboundQueuedBytes(outbound)
			.setCloseOnInboundEof(closeOnEof);
	}

	private static byte[] bytes(ByteBuf buffer) {
		byte[] bytes = new byte[buffer.readableBytes()];
		buffer.getBytes(buffer.readerIndex(), bytes);
		return bytes;
	}

	private static byte[] randomBytes(int size, long seed) {
		byte[] bytes = new byte[size];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

	private static void awaitCondition(CheckedBoolean condition, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(condition.get()).isTrue();
	}

	private static boolean noPipeThreads() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(Thread::isAlive)
			.noneMatch(thread -> thread.getName().startsWith("pipe-reader-")
					|| thread.getName().startsWith("pipe-writer-"));
	}

	private static List<String> blockedPipeIoThreads() {
		return Thread.getAllStackTraces()
			.entrySet()
			.stream()
			.filter(entry -> entry.getKey().isAlive())
			.filter(entry -> entry.getKey().getName().startsWith("pipe-"))
			.filter(entry -> java.util.Arrays.stream(entry.getValue())
				.anyMatch(frame -> frame.getClassName().startsWith("java.io.")
						&& (frame.getMethodName().contains("read") || frame.getMethodName().contains("write"))))
			.map(entry -> entry.getKey().getName())
			.toList();
	}

	private record Fixture(PipeHttp2Client client, PipeHttp2Server server) {
	}

	private static void schedulePressureDrain(PipeHttp2Client client, ConcurrentLinkedQueue<PendingData> pending,
			AtomicBoolean scheduled, ByteArrayOutputStream actual, CompletableFuture<Void> complete) {
		if (!scheduled.compareAndSet(false, true)) {
			return;
		}
		client.channel().eventLoop().schedule(() -> {
			int budget = 8 * 1024;
			boolean endSeen = false;
			while (budget > 0) {
				PendingData next = pending.peek();
				if (next == null) {
					break;
				}
				int count = Math.min(budget, next.data().readableBytes());
				byte[] chunk = new byte[count];
				next.data().readBytes(chunk);
				actual.writeBytes(chunk);
				budget -= count;
				if (!next.data().isReadable()) {
					pending.remove();
					endSeen |= next.endStream();
					next.data().release();
				}
			}
			scheduled.set(false);
			if (endSeen) {
				complete.complete(null);
			}
			else if (!pending.isEmpty()) {
				schedulePressureDrain(client, pending, scheduled, actual, complete);
			}
		}, 5, TimeUnit.MILLISECONDS);
	}

	private record PendingData(ByteBuf data, boolean endStream) {
	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

}
