package io.modelcontextprotocol.transport.httpstdio.client;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Client;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.modelcontextprotocol.transport.httpstdio.process.ChildProcessLauncher;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import reactor.core.publisher.Mono;

/** Owns a pipe HTTP/2 client and its optional child process. */
public final class HttpOverStdioClientTransport implements AutoCloseable {

	private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);

	private final EventLoopGroup eventLoopGroup;

	private final PipeHttp2Client client;

	private final NettyHttp2Exchange exchange;

	private final ChildProcessLauncher child;

	/**
	 * Runs the close sequence at most once, on first subscription, and replays its
	 * outcome. A second sequence would write to a channel whose event loop has stopped,
	 * and that write's future never completes.
	 */
	private final Mono<Void> closeOnce = Mono.defer(this::closeSequence).cache();

	private HttpOverStdioClientTransport(EventLoopGroup eventLoopGroup, PipeHttp2Client client,
			ChildProcessLauncher child) {
		this.eventLoopGroup = eventLoopGroup;
		this.client = client;
		this.exchange = new NettyHttp2Exchange(client);
		this.child = child;
	}

	public static CompletableFuture<HttpOverStdioClientTransport> connect(DuplexByteChannel duplex) {
		return connect(duplex, null);
	}

	public static CompletableFuture<HttpOverStdioClientTransport> connect(DuplexByteChannel duplex,
			PipeChannelConfig config) {
		EventLoopGroup group = new DefaultEventLoopGroup();
		return PipeHttp2Client.connect(duplex, group, config)
			.thenApply(client -> new HttpOverStdioClientTransport(group, client, null));
	}

	public static CompletableFuture<HttpOverStdioClientTransport> launch(List<String> command,
			Map<String, String> environment, java.util.function.Consumer<String> stderrLineHandler) throws IOException {
		ChildProcessLauncher child = ChildProcessLauncher.launch(command, environment, stderrLineHandler);
		EventLoopGroup group = new DefaultEventLoopGroup();
		return PipeHttp2Client.connect(child.duplex(), group, null)
			.thenApply(client -> new HttpOverStdioClientTransport(group, client, child));
	}

	public NettyHttp2Exchange exchange() {
		return this.exchange;
	}

	/**
	 * Returns the launched child process, or empty when this transport was connected to a
	 * caller-supplied channel.
	 * @return the child process
	 */
	public java.util.Optional<Process> childProcess() {
		return java.util.Optional.ofNullable(this.child).map(ChildProcessLauncher::process);
	}

	public HttpClientStreamableHttpTransport streamableTransport(String baseUri) {
		return HttpClientStreamableHttpTransport.builder(baseUri).httpExchange(this.exchange).build();
	}

	public Mono<Void> closeGracefully() {
		return this.closeOnce;
	}

	private Mono<Void> closeSequence() {
		// GOAWAY, then drain so the frame actually reaches the child before anything is
		// closed; closing the output also closes the child's stdin, its normal exit
		// signal. Only then wait for the child, close the channel, and stop the loop.
		// Each step is a supplier so it starts only after the previous one completes;
		// Mono.fromFuture(future) would start every step at assembly time.
		return Mono.fromFuture(this.client::goAway)
			.onErrorComplete()
			.then(Mono.fromFuture(this.client::drainOutput).timeout(SHUTDOWN_GRACE).onErrorComplete())
			.then(Mono
				.fromFuture(() -> this.child == null ? CompletableFuture.completedFuture(0)
						: this.child.shutdown(SHUTDOWN_GRACE, SHUTDOWN_GRACE))
				.onErrorComplete())
			.then(Mono.fromFuture(this.client::close).onErrorComplete())
			.then(Mono.create(sink -> this.eventLoopGroup
				.shutdownGracefully(0, SHUTDOWN_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
				.addListener(future -> {
					if (future.isSuccess()) {
						sink.success();
					}
					else {
						sink.error(future.cause());
					}
				})));
	}

	@Override
	public void close() {
		closeGracefully().block(SHUTDOWN_GRACE.multipliedBy(3));
	}

}
