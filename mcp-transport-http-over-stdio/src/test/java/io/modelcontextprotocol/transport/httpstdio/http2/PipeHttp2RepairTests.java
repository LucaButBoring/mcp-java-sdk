package io.modelcontextprotocol.transport.httpstdio.http2;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.NettyPipeChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipeHttp2RepairTests {

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
	void borrowedRequestBodyIsReleasedExactlyOnceAfterHandlerReturns() throws Exception {
		AtomicReference<ByteBuf> borrowed = new AtomicReference<>();
		Fixture fixture = fixture(4096, null, null, PipeHttp2Server.DEFAULT_MAX_REQUEST_BODY_BYTES,
				PipeHttp2Client.DEFAULT_MAX_RESPONSE_BODY_BYTES, (request, response) -> {
					borrowed.set(request.body());
					assertThat(bytes(request.body())).containsExactly(1, 2, 3, 4);
					response.end();
				});

		Http2Response response = fixture.client
			.request("POST", "/borrowed", null, Unpooled.wrappedBuffer(new byte[] { 1, 2, 3, 4 }))
			.get(5, TimeUnit.SECONDS);
		response.body().release();
		await(() -> borrowed.get() != null && borrowed.get().refCnt() == 0, Duration.ofSeconds(5));
	}

	@Test
	void fastCompletingStreamDeliversEveryFrameAndCompletion() throws Exception {
		Fixture fixture = fixture((request, response) -> {
			response.headers("200", new DefaultHttp2Headers().add("x-fast", "yes"));
			response.data(Unpooled.wrappedBuffer(new byte[] { 1, 2 }), false);
			response.data(Unpooled.wrappedBuffer(new byte[] { 3, 4 }), true);
		});
		ByteArrayOutputStream received = new ByteArrayOutputStream();
		AtomicInteger headers = new AtomicInteger();
		AtomicInteger completions = new AtomicInteger();
		AtomicBoolean endSeen = new AtomicBoolean();

		StreamingResponse stream = fixture.client.requestStreaming("GET", "/fast", null, Unpooled.EMPTY_BUFFER,
				new StreamingResponse.Listener() {
					@Override
					public void onHeaders(io.netty.handler.codec.http2.Http2Headers value, boolean endStream) {
						headers.incrementAndGet();
					}

					@Override
					public void onData(ByteBuf data, boolean endStream) {
						byte[] chunk = bytes(data);
						received.writeBytes(chunk);
						data.release();
						endSeen.set(endStream);
					}

					@Override
					public void onComplete() {
						completions.incrementAndGet();
					}
				});

		stream.completion().get(5, TimeUnit.SECONDS);
		assertThat(headers).hasValue(1);
		assertThat(received.toByteArray()).containsExactly(1, 2, 3, 4);
		assertThat(endSeen).isTrue();
		assertThat(completions).hasValue(1);
	}

	@Test
	void immediateCancellationSendsExactlyOneReset() throws Exception {
		AtomicInteger cancellations = new AtomicInteger();
		Fixture fixture = fixture((request, response) -> response.onCancelled(cancellations::incrementAndGet));

		StreamingResponse stream = fixture.client.requestStreaming("GET", "/cancel", null, Unpooled.EMPTY_BUFFER,
				new StreamingResponse.Listener() {
				});
		CompletableFuture<Void> first = stream.cancel();
		CompletableFuture<Void> second = stream.cancel();
		assertThat(second).as("repeated cancel() calls return the single retained cancellation future").isSameAs(first);
		first.get(5, TimeUnit.SECONDS);
		// The future completes once RST_STREAM is written and flushed locally; the peer
		// observes it asynchronously, so its counter is awaited and then must stay at
		// one.
		await(() -> cancellations.get() == 1, Duration.ofSeconds(5));
		CompletableFuture<Void> third = stream.cancel();
		assertThat(third).isSameAs(first);
		assertThat(third).isDone();
		assertThat(cancellations).hasValue(1);
	}

	@Test
	void oversizedRequestStreamsFailWithoutAffectingNormalOrLaterStreams() throws Exception {
		Fixture fixture = fixture(4096, null, null, 1024, PipeHttp2Client.DEFAULT_MAX_RESPONSE_BODY_BYTES,
				(request, response) -> {
					response.headers("200", null);
					response.data(Unpooled.wrappedBuffer(new byte[] { 7 }), true);
				});
		CompletableFuture<Http2Response> oversizedOne = fixture.client.request("POST", "/too-big-1", null,
				Unpooled.wrappedBuffer(randomBytes(2048, 1)));
		CompletableFuture<Http2Response> oversizedTwo = fixture.client.request("POST", "/too-big-2", null,
				Unpooled.wrappedBuffer(randomBytes(2048, 2)));
		CompletableFuture<Http2Response> normal = fixture.client.request("GET", "/normal", null, Unpooled.EMPTY_BUFFER);

		assertThat(failure(oversizedOne)).isInstanceOf(Http2BodyTooLargeException.class);
		assertThat(failure(oversizedTwo)).isInstanceOf(Http2BodyTooLargeException.class);
		Http2Response normalResponse = normal.get(5, TimeUnit.SECONDS);
		normalResponse.body().release();
		Http2Response later = fixture.client.request("GET", "/later", null, Unpooled.EMPTY_BUFFER)
			.get(5, TimeUnit.SECONDS);
		later.body().release();
		assertThat(fixture.client.channel().isActive()).isTrue();
		assertThat(fixture.server.channel().isActive()).isTrue();
	}

	@Test
	void oversizedResponseStreamsFailWithoutAffectingNormalStream() throws Exception {
		Fixture fixture = fixture(4096, null, null, PipeHttp2Server.DEFAULT_MAX_REQUEST_BODY_BYTES, 1024,
				(request, response) -> {
					if (request.path().startsWith("/large")) {
						response.data(Unpooled.wrappedBuffer(randomBytes(2048, request.path().hashCode())), true);
					}
					else {
						response.data(Unpooled.wrappedBuffer(new byte[] { 9 }), true);
					}
				});
		CompletableFuture<Http2Response> oversizedOne = fixture.client.request("GET", "/large-1", null,
				Unpooled.EMPTY_BUFFER);
		CompletableFuture<Http2Response> oversizedTwo = fixture.client.request("GET", "/large-2", null,
				Unpooled.EMPTY_BUFFER);
		Http2Response normal = fixture.client.request("GET", "/normal", null, Unpooled.EMPTY_BUFFER)
			.get(5, TimeUnit.SECONDS);
		normal.body().release();

		assertThat(failure(oversizedOne)).isInstanceOf(Http2BodyTooLargeException.class);
		assertThat(failure(oversizedTwo)).isInstanceOf(Http2BodyTooLargeException.class);
		assertThat(fixture.client.channel().isActive()).isTrue();
		assertThat(fixture.server.channel().isActive()).isTrue();
	}

	@Test
	void connectFailsWhenPeerClosesBeforeSettings() throws Exception {
		DuplexByteChannel[] duplex = InMemoryDuplexByteChannel.pair(4096);
		DefaultEventLoopGroup clientGroup = group("early-eof-client");
		duplex[1].close();
		CompletableFuture<PipeHttp2Client> connecting = PipeHttp2Client.connect(duplex[0], clientGroup, null);
		assertThat(failure(connecting)).isInstanceOf(Http2TransportException.class);
	}

	@Test
	void requestFailsWhenServerClosesMidResponse() throws Exception {
		PipeHttp2Server[] holder = new PipeHttp2Server[1];
		Fixture fixture = fixture((request, response) -> {
			response.headers("200", null);
			response.data(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }), false);
			holder[0].channel().eventLoop().execute(() -> holder[0].close());
		});
		holder[0] = fixture.server;

		CompletableFuture<Http2Response> request = fixture.client.request("GET", "/abrupt", null,
				Unpooled.EMPTY_BUFFER);
		assertThat(failure(request)).isInstanceOf(Http2TransportException.class);
	}

	@Test
	void bidirectionalFlowControlPressureNoDeadlock() throws Exception {
		PipeChannelConfig clientConfig = config(4096, 16 * 1024, 16 * 1024, true);
		PipeChannelConfig serverConfig = config(4096, 16 * 1024, 16 * 1024, true);
		byte[] upload = randomBytes(4 * 1024 * 1024, 77);
		byte[] download = randomBytes(4 * 1024 * 1024, 88);
		PipeHttp2Server[] holder = new PipeHttp2Server[1];
		Fixture fixture = fixture(4096, clientConfig, serverConfig, PipeHttp2Server.DEFAULT_MAX_REQUEST_BODY_BYTES,
				PipeHttp2Client.DEFAULT_MAX_RESPONSE_BODY_BYTES, (request, response) -> {
					if ("/download".equals(request.path())) {
						response.headers("200", null);
						for (int offset = 0; offset < download.length; offset += 8 * 1024) {
							int count = Math.min(8 * 1024, download.length - offset);
							response.data(Unpooled.wrappedBuffer(download, offset, count),
									offset + count == download.length);
						}
					}
					else {
						ByteBuf retained = request.body().retainedDuplicate();
						holder[0].channel().eventLoop().schedule(() -> {
							try {
								assertThat(bytes(retained)).isEqualTo(upload);
								response.end();
							}
							finally {
								retained.release();
							}
						}, 250, TimeUnit.MILLISECONDS);
					}
				});
		holder[0] = fixture.server;

		ByteArrayOutputStream actual = new ByteArrayOutputStream(download.length);
		CompletableFuture<Void> downloadComplete = new CompletableFuture<>();
		ConcurrentLinkedQueue<PendingData> pending = new ConcurrentLinkedQueue<>();
		AtomicBoolean drainScheduled = new AtomicBoolean();
		StreamingResponse stream = fixture.client.requestStreaming("GET", "/download", null, Unpooled.EMPTY_BUFFER,
				new StreamingResponse.Listener() {
					@Override
					public void onData(ByteBuf data, boolean endStream) {
						pending.add(new PendingData(data, endStream));
						scheduleDrain(fixture.client, pending, drainScheduled, actual, downloadComplete);
					}
				});
		CompletableFuture<Http2Response> uploadComplete = fixture.client.request("POST", "/upload", null,
				Unpooled.wrappedBuffer(upload));

		CompletableFuture.allOf(stream.completion(), downloadComplete, uploadComplete).get(60, TimeUnit.SECONDS);
		uploadComplete.join().body().release();
		assertThat(actual.toByteArray()).isEqualTo(download);
		assertThat(fixture.client.channel().metrics().readerBlockedNanos()).isPositive();
		assertThat(fixture.client.channel().metrics().writerBlockedNanos()).isPositive();
		assertThat(fixture.server.channel().metrics().readerBlockedNanos()).isPositive();
		assertThat(fixture.server.channel().metrics().writerBlockedNanos()).isPositive();
	}

	private Fixture fixture(Http2RequestHandler handler) throws Exception {
		return fixture(4096, null, null, PipeHttp2Server.DEFAULT_MAX_REQUEST_BODY_BYTES,
				PipeHttp2Client.DEFAULT_MAX_RESPONSE_BODY_BYTES, handler);
	}

	private Fixture fixture(int pipeBytes, PipeChannelConfig clientConfig, PipeChannelConfig serverConfig,
			long maxRequestBytes, long maxResponseBytes, Http2RequestHandler handler) throws Exception {
		DuplexByteChannel[] duplex = InMemoryDuplexByteChannel.pair(pipeBytes);
		DefaultEventLoopGroup clientGroup = group("repair-client");
		DefaultEventLoopGroup serverGroup = group("repair-server");
		PipeHttp2Server server = PipeHttp2Server.start(duplex[1], serverGroup, serverConfig, maxRequestBytes, handler);
		PipeHttp2Client client = PipeHttp2Client.connect(duplex[0], clientGroup, clientConfig, maxResponseBytes)
			.get(5, TimeUnit.SECONDS);
		this.channels.add(client.channel());
		this.channels.add(server.channel());
		return new Fixture(client, server);
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

	private static Throwable failure(CompletableFuture<?> future) throws Exception {
		try {
			future.get(5, TimeUnit.SECONDS);
			throw new AssertionError("future completed successfully");
		}
		catch (java.util.concurrent.ExecutionException failure) {
			Throwable cause = failure.getCause();
			while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			return cause;
		}
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

	private static void await(CheckedBoolean condition, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(condition.get()).isTrue();
	}

	private static void scheduleDrain(PipeHttp2Client client, ConcurrentLinkedQueue<PendingData> pending,
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
				scheduleDrain(client, pending, scheduled, actual, complete);
			}
		}, 5, TimeUnit.MILLISECONDS);
	}

	private record Fixture(PipeHttp2Client client, PipeHttp2Server server) {
	}

	private record PendingData(ByteBuf data, boolean endStream) {
	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

}
