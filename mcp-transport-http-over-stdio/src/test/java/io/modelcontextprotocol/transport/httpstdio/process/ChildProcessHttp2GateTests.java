package io.modelcontextprotocol.transport.httpstdio.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.transport.httpstdio.http2.Http2Response;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Client;
import io.modelcontextprotocol.transport.httpstdio.http2.StreamingResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChildProcessHttp2GateTests {

	@Test
	void realChildProcessHttp2Gate() throws Exception {
		String java = ProcessHandle.current().info().command().orElseThrow();
		CopyOnWriteArrayList<String> stderr = new CopyOnWriteArrayList<>();
		ChildProcessLauncher launcher = ChildProcessLauncher.launch(
				List.of(java, "-cp", System.getProperty("java.class.path"), EchoHttp2ChildMain.class.getName()),
				Map.of(), stderr::add);
		DefaultEventLoopGroup group = new DefaultEventLoopGroup(2, new DefaultThreadFactory("process-gate-client"));
		PipeHttp2Client client = null;
		try {
			client = PipeHttp2Client.connect(launcher.duplex(), group, null).get(20, TimeUnit.SECONDS);
			await(() -> stderr.contains("ready"), Duration.ofSeconds(20));
			List<byte[]> expected = new ArrayList<>();
			List<CompletableFuture<Http2Response>> requests = new ArrayList<>();
			for (int index = 0; index < 8; index++) {
				byte[] body = randomBytes(256 * 1024, 500 + index);
				expected.add(body);
				requests.add(client.request("POST", "/echo", null, Unpooled.wrappedBuffer(body)));
			}
			CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
			for (int index = 0; index < requests.size(); index++) {
				Http2Response response = requests.get(index).join();
				try {
					assertThat(bytes(response.body())).isEqualTo(expected.get(index));
				}
				finally {
					response.body().release();
				}
			}

			CountDownLatch frames = new CountDownLatch(5);
			StreamingResponse slow = client.requestStreaming("GET", "/slow", null, Unpooled.EMPTY_BUFFER,
					new StreamingResponse.Listener() {
						@Override
						public void onData(ByteBuf data, boolean end) {
							data.release();
							frames.countDown();
						}
					});
			CompletableFuture<Http2Response> ping = client.request("GET", "/ping", null, Unpooled.EMPTY_BUFFER);
			assertThat(frames.await(10, TimeUnit.SECONDS)).isTrue();
			slow.cancel().get(5, TimeUnit.SECONDS);
			Http2Response pingResponse = ping.get(5, TimeUnit.SECONDS);
			try {
				assertThat(bytes(pingResponse.body())).isEqualTo(new byte[] { 'p', 'o', 'n', 'g' });
			}
			finally {
				pingResponse.body().release();
			}
			await(() -> stderr.contains("cancelled=1"), Duration.ofSeconds(10));
			client.goAway().get(5, TimeUnit.SECONDS);
			await(() -> stderr.contains("goaway-received"), Duration.ofSeconds(10));
			client.close().get(5, TimeUnit.SECONDS);
			int exitCode = launcher.shutdown(Duration.ofSeconds(2), Duration.ofSeconds(2)).get(6, TimeUnit.SECONDS);
			assertThat(stderr).contains("goaway-received");
			assertThat(exitCode).isZero();
			assertThat(stderr).noneMatch(line -> line.contains("PRI * HTTP/2.0"));
		}
		finally {
			if (client != null) {
				client.close().exceptionally(ignored -> null).get(5, TimeUnit.SECONDS);
			}
			if (launcher.process().isAlive()) {
				launcher.shutdown(Duration.ofSeconds(1), Duration.ofSeconds(1)).get(4, TimeUnit.SECONDS);
			}
			group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
		await(ChildProcessHttp2GateTests::noPipeThreads, Duration.ofSeconds(5));
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

	private static boolean noPipeThreads() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(Thread::isAlive)
			.noneMatch(thread -> thread.getName().startsWith("pipe-reader-")
					|| thread.getName().startsWith("pipe-writer-"));
	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

}
