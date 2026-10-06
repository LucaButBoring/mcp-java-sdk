package io.modelcontextprotocol.transport.httpstdio.server;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.http.SseEvent;
import io.modelcontextprotocol.server.transport.http.StatelessHttpDispatcher;
import io.modelcontextprotocol.server.transport.http.StatelessHttpRequest;
import io.modelcontextprotocol.server.transport.http.StatelessHttpResponse;
import io.modelcontextprotocol.server.transport.http.StatelessServerHandlerAdapter;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2Request;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2ResponseWriter;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/** Stateless MCP server transport carried over a prior-knowledge HTTP/2 stdio pipe. */
public final class HttpOverStdioServerTransport implements McpStatelessServerTransport {

	private static final long DEFAULT_REQUEST_MAX_SIZE = 16L * 1024 * 1024;

	private final EventLoopGroup eventLoopGroup;

	private final boolean ownsEventLoopGroup;

	private final McpJsonMapper jsonMapper;

	private final long requestMaxSize;

	private final AtomicBoolean closing = new AtomicBoolean();

	private final PipeHttp2Server server;

	/** Runs the close sequence at most once, on first subscription, and replays it. */
	private final Mono<Void> closeOnce = Mono.defer(this::closeSequence).cache();

	private volatile StatelessHttpDispatcher dispatcher;

	private HttpOverStdioServerTransport(DuplexByteChannel duplex, EventLoopGroup eventLoopGroup,
			boolean ownsEventLoopGroup, PipeChannelConfig config, McpJsonMapper jsonMapper, long requestMaxSize) {
		this.eventLoopGroup = eventLoopGroup;
		this.ownsEventLoopGroup = ownsEventLoopGroup;
		this.jsonMapper = jsonMapper;
		this.requestMaxSize = requestMaxSize;
		this.server = PipeHttp2Server.start(duplex, eventLoopGroup, config, requestMaxSize, this::handle);
	}

	public static HttpOverStdioServerTransport start(DuplexByteChannel duplex) {
		return start(duplex, null, McpJsonDefaults.getMapper());
	}

	/**
	 * Serves this process's own stdin and stdout, reserving stdout for HTTP/2 frames.
	 *
	 * <p>
	 * The protocol bytes are written to the raw {@link java.io.FileDescriptor#out}
	 * descriptor. Anything else that writes to {@link System#out} -- a logging backend's
	 * default console appender, a stray {@code println} -- would interleave text with
	 * HTTP/2 frames and corrupt the connection, so {@code System.out} is redirected to
	 * {@code System.err} before the server starts. Call this before any other component
	 * caches a reference to {@code System.out}.
	 * @param eventLoopGroup event loop group owned by the caller
	 * @param config pipe channel configuration, or {@code null} for defaults
	 * @param jsonMapper JSON mapper used by the dispatcher
	 * @return the started transport
	 */
	public static HttpOverStdioServerTransport startOnCurrentProcessStdio(EventLoopGroup eventLoopGroup,
			PipeChannelConfig config, McpJsonMapper jsonMapper) {
		DuplexByteChannel stdio = io.modelcontextprotocol.transport.httpstdio.pipe.ProcessPipeDuplexByteChannel
			.forCurrentProcess();
		System.setOut(System.err);
		return start(stdio, eventLoopGroup, config, jsonMapper);
	}

	public static HttpOverStdioServerTransport start(DuplexByteChannel duplex, PipeChannelConfig config,
			McpJsonMapper jsonMapper) {
		return new HttpOverStdioServerTransport(duplex, new DefaultEventLoopGroup(), true, config, jsonMapper,
				DEFAULT_REQUEST_MAX_SIZE);
	}

	public static HttpOverStdioServerTransport start(DuplexByteChannel duplex, EventLoopGroup eventLoopGroup,
			PipeChannelConfig config, McpJsonMapper jsonMapper) {
		return new HttpOverStdioServerTransport(duplex, eventLoopGroup, false, config, jsonMapper,
				DEFAULT_REQUEST_MAX_SIZE);
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.dispatcher = new StatelessHttpDispatcher(this.jsonMapper, StatelessServerHandlerAdapter.adapt(mcpHandler),
				this.requestMaxSize, this.closing::get,
				io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator.NOOP);
	}

	public PipeHttp2Server server() {
		return this.server;
	}

	private void handle(Http2Request request, Http2ResponseWriter writer) {
		StatelessHttpDispatcher current = this.dispatcher;
		if (current == null) {
			writeEmpty(writer, 503, Map.of());
			return;
		}
		String body = request.body().toString(StandardCharsets.UTF_8);
		NettyHeaderAccessor headers = new NettyHeaderAccessor(request.headers());
		Optional<Long> contentLength = headers.getHeader("content-length")
			.stream()
			.findFirst()
			.flatMap(HttpOverStdioServerTransport::parseContentLength);
		StatelessHttpRequest statelessRequest = new StatelessHttpRequest(request.method(), request.path(), headers,
				contentLength, body, McpTransportContext.EMPTY);
		current.dispatch(statelessRequest)
			.subscribe(response -> writeResponse(writer, response), failure -> writeFailure(writer));
	}

	private static Optional<Long> parseContentLength(String value) {
		try {
			return Optional.of(Long.parseLong(value));
		}
		catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}

	/** Package-private for tests: writes one dispatcher response to an HTTP/2 stream. */
	static void writeResponse(Http2ResponseWriter writer, StatelessHttpResponse response) {
		if (response.body() instanceof StatelessHttpResponse.Empty) {
			writeEmpty(writer, response.status(), response.headers());
			return;
		}
		if (response.body() instanceof StatelessHttpResponse.Json json) {
			Http2Headers headers = responseHeaders(response.headers(), "application/json");
			writer.headers(Integer.toString(response.status()), headers);
			writer.data(Unpooled.copiedBuffer(json.text(), StandardCharsets.UTF_8), true);
			return;
		}
		StatelessHttpResponse.Sse sse = (StatelessHttpResponse.Sse) response.body();
		writer.headers(Integer.toString(response.status()), responseHeaders(response.headers(), "text/event-stream"));
		Disposable subscription = sse.events()
			.subscribe(event -> writer.data(Unpooled.copiedBuffer(encodeSse(event), StandardCharsets.UTF_8), false),
					failure -> writer.reset(Http2Error.INTERNAL_ERROR.code()), writer::end);
		writer.onCancelled(subscription::dispose);
	}

	private static void writeEmpty(Http2ResponseWriter writer, int status, Map<String, List<String>> headers) {
		writer.headers(Integer.toString(status), responseHeaders(headers, null));
		writer.end();
	}

	private static void writeFailure(Http2ResponseWriter writer) {
		writer.headers("500", responseHeaders(Map.of(), "application/json"));
		writer.data(Unpooled.copiedBuffer(
				"{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal server error\"}}",
				StandardCharsets.UTF_8), true);
	}

	private static Http2Headers responseHeaders(Map<String, List<String>> responseHeaders, String defaultContentType) {
		Http2Headers headers = new DefaultHttp2Headers();
		boolean hasContentType = false;
		for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
			if ("content-type".equalsIgnoreCase(entry.getKey())) {
				hasContentType = true;
			}
			for (String value : entry.getValue()) {
				headers.add(entry.getKey().toLowerCase(java.util.Locale.ROOT), value);
			}
		}
		if (defaultContentType != null && !hasContentType) {
			headers.add("content-type", defaultContentType);
		}
		return headers;
	}

	private static String encodeSse(SseEvent event) {
		StringBuilder encoded = new StringBuilder();
		event.id().ifPresent(id -> encoded.append("id: ").append(id).append('\n'));
		return encoded.append("event: ")
			.append(event.event())
			.append('\n')
			.append("data: ")
			.append(event.data())
			.append("\n\n")
			.toString();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.closeOnce;
	}

	private Mono<Void> closeSequence() {
		// Suppliers keep each step from starting before the previous one completes.
		return Mono.fromRunnable(() -> this.closing.set(true))
			.then(Mono.fromFuture(this.server::goAway).onErrorComplete())
			.then(Mono.fromFuture(this.server::drainOutput).timeout(java.time.Duration.ofSeconds(5)).onErrorComplete())
			.then(Mono.fromFuture(this.server::close).onErrorComplete())
			.then(this.ownsEventLoopGroup ? Mono
				.create(sink -> this.eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).addListener(future -> {
					if (future.isSuccess()) {
						sink.success();
					}
					else {
						sink.error(future.cause());
					}
				})) : Mono.empty());
	}

}
