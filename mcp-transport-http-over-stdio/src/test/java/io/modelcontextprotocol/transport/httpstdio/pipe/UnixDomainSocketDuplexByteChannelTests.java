package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.client.transport.http.McpHttpResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.http.McpStatelessServerResult;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.server.HttpOverStdioServerTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared pipe contract, run over the Unix-domain-socket fallback wire: the same
 * {@link NettyPipeChannel} and HTTP/2 stack, a different {@link DuplexByteChannel}.
 */
@Timeout(60)
class UnixDomainSocketDuplexByteChannelTests {

	private Path directory;

	private ServerSocketChannel listener;

	private DuplexByteChannel clientEnd;

	private DuplexByteChannel serverEnd;

	private DefaultEventLoopGroup group;

	@BeforeEach
	void connect() throws Exception {
		ServerSocketChannel server;
		try {
			server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		}
		catch (UnsupportedOperationException unsupported) {
			assumeTrue(false, "AF_UNIX sockets are not supported on this platform");
			return;
		}
		// A short path: AF_UNIX socket paths are limited to roughly 100 bytes.
		this.directory = Files.createTempDirectory("mcp-uds");
		this.listener = server;
		this.listener.bind(UnixDomainSocketAddress.of(this.directory.resolve("s")));
		CompletableFuture<DuplexByteChannel> accepted = CompletableFuture.supplyAsync(() -> {
			try {
				return UnixDomainSocketDuplexByteChannel.accept(this.listener);
			}
			catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		});
		this.clientEnd = UnixDomainSocketDuplexByteChannel.connect(this.directory.resolve("s"));
		this.serverEnd = accepted.get(10, TimeUnit.SECONDS);
		this.group = new DefaultEventLoopGroup(2);
	}

	@AfterEach
	void close() throws Exception {
		if (this.clientEnd != null) {
			this.clientEnd.close();
		}
		if (this.serverEnd != null) {
			this.serverEnd.close();
		}
		if (this.listener != null) {
			this.listener.close();
		}
		if (this.group != null) {
			this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
		}
		if (this.directory != null) {
			Files.deleteIfExists(this.directory.resolve("s"));
			Files.deleteIfExists(this.directory);
		}
	}

	@Test
	void bidirectionalPressureBeyondKernelBuffersCompletes() throws Exception {
		NettyPipeChannel a = register(this.clientEnd, 16 * 1024);
		NettyPipeChannel b = register(this.serverEnd, 16 * 1024);
		byte[] fromA = random(4 * 1024 * 1024, 1);
		byte[] fromB = random(4 * 1024 * 1024, 2);
		CompletableFuture<byte[]> atB = collect(b, fromA.length);
		CompletableFuture<byte[]> atA = collect(a, fromB.length);
		CompletableFuture.allOf(write(a, fromA), write(b, fromB)).get(30, TimeUnit.SECONDS);
		assertThat(atB.get(30, TimeUnit.SECONDS)).isEqualTo(fromA);
		assertThat(atA.get(30, TimeUnit.SECONDS)).isEqualTo(fromB);
		a.close().sync();
		b.close().sync();
	}

	@Test
	void shutdownOutputIsDirectionalAndCloseIsEndOfStreamForThePeer() throws Exception {
		NettyPipeChannel a = register(this.clientEnd, 64 * 1024);
		NettyPipeChannel b = register(this.serverEnd, 64 * 1024);
		b.config().setCloseOnInboundEof(false);
		a.config().setCloseOnInboundEof(false);
		CountDownLatch eofAtB = eofLatch(b);
		CountDownLatch eofAtA = eofLatch(a);
		CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
		a.pipeline().addLast(errorRecorder(errors));
		b.pipeline().addLast(errorRecorder(errors));

		a.shutdownOutput().sync();
		assertThat(eofAtB.await(5, TimeUnit.SECONDS)).as("half-close reaches the peer as EOF").isTrue();
		byte[] reply = random(64 * 1024, 3);
		CompletableFuture<byte[]> atA = collect(a, reply.length);
		write(b, reply).get(5, TimeUnit.SECONDS);
		assertThat(atA.get(5, TimeUnit.SECONDS)).as("the reverse direction stays usable").isEqualTo(reply);

		b.close().sync();
		assertThat(eofAtA.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(errors).isEmpty();
		a.close().sync();
	}

	@Test
	void mcpRequestsRoundTripConcurrentlyOverTheSocket() throws Exception {
		HttpOverStdioServerTransport server = HttpOverStdioServerTransport.builder(this.serverEnd)
			.eventLoopGroup(this.group)
			.build();
		server.setStreamingHandler(exchange -> {
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) exchange.message();
			return Mono.just(new McpStatelessServerResult.Single(
					McpSchema.JSONRPCResponse.result(request.id(), "ok-" + request.id())));
		});
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport.connect(this.clientEnd)
			.get(10, TimeUnit.SECONDS);
		try {
			List<String> bodies = Flux.range(0, 16)
				.flatMap(n -> client.exchange().exchange(post(n), McpTransportContext.EMPTY).flatMap(response -> {
					assertThat(response.statusCode()).isEqualTo(200);
					return body(response);
				}), 16)
				.collectList()
				.block(Duration.ofSeconds(30));
			for (int n = 0; n < 16; n++) {
				String expected = "\"result\":\"ok-" + n + "\"";
				assertThat(bodies).anyMatch(body -> body.contains(expected));
			}
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(10));
			server.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	private NettyPipeChannel register(DuplexByteChannel duplex, int queueBytes) throws Exception {
		NettyPipeChannel channel = new NettyPipeChannel(duplex);
		channel.config().setMaxOutboundQueuedBytes(queueBytes);
		this.group.register(channel).sync();
		return channel;
	}

	private static McpHttpRequest post(int n) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + n + "\",\"method\":\"tools/list\",\"params\":{\"_meta\":"
				+ "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}";
		return McpHttpRequest.builder()
			.method("POST")
			.uri(URI.create("http://pipe/mcp"))
			.headers(McpHttpHeaders.builder()
				.add("Accept", "application/json, text/event-stream")
				.add("MCP-Protocol-Version", "2026-07-28")
				.add("Mcp-Method", "tools/list")
				.build())
			.body(body)
			.build();
	}

	private static Mono<String> body(McpHttpResponse response) {
		return JdkFlowAdapter.flowPublisherToFlux(response.body())
			.flatMapIterable(values -> values)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
			.collect(Collectors.joining());
	}

	private static CountDownLatch eofLatch(NettyPipeChannel channel) {
		CountDownLatch eof = new CountDownLatch(1);
		channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void userEventTriggered(ChannelHandlerContext context, Object event) {
				if (event == InboundEofEvent.INSTANCE) {
					eof.countDown();
				}
				context.fireUserEventTriggered(event);
			}
		});
		return eof;
	}

	private static ChannelInboundHandlerAdapter errorRecorder(List<Throwable> errors) {
		return new ChannelInboundHandlerAdapter() {
			@Override
			public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
				errors.add(cause);
			}
		};
	}

	private static CompletableFuture<byte[]> collect(NettyPipeChannel channel, int expected) {
		CompletableFuture<byte[]> result = new CompletableFuture<>();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		channel.pipeline().addFirst(new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext context, Object message) {
				ByteBuf buffer = (ByteBuf) message;
				try {
					byte[] chunk = new byte[buffer.readableBytes()];
					buffer.readBytes(chunk);
					bytes.writeBytes(chunk);
					if (bytes.size() >= expected) {
						result.complete(bytes.toByteArray());
					}
				}
				finally {
					ReferenceCountUtil.release(buffer);
				}
			}
		});
		return result;
	}

	private static CompletableFuture<Void> write(NettyPipeChannel channel, byte[] bytes) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		writeFrom(channel, bytes, 0, done);
		return done;
	}

	private static void writeFrom(NettyPipeChannel channel, byte[] bytes, int offset, CompletableFuture<Void> done) {
		if (offset == bytes.length) {
			done.complete(null);
			return;
		}
		int count = Math.min(16 * 1024, bytes.length - offset);
		channel.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, count)).addListener(future -> {
			if (future.isSuccess()) {
				writeFrom(channel, bytes, offset + count, done);
			}
			else {
				done.completeExceptionally(future.cause());
			}
		});
	}

	private static byte[] random(int length, long seed) {
		byte[] bytes = new byte[length];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

}
