package io.modelcontextprotocol.transport.httpstdio.server;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2Request;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2RequestHandler;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2ResponseWriter;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.modelcontextprotocol.transport.httpstdio.pipe.ProcessPipeDuplexByteChannel;
import io.modelcontextprotocol.util.Assert;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stateless MCP server transport carried over a prior-knowledge HTTP/2 stdio pipe,
 * implementing the MCP 2026-07-28 Streamable HTTP shape.
 *
 * <p>
 * Requests whose path is the MCP endpoint are dispatched by
 * {@link StatelessHttpDispatcher}: POST only (GET, DELETE, and other methods answer 405),
 * Origin and optional logical-authority validation, the 2026-07-28 request-metadata
 * binding ({@link McpHttpBinding2026}), and JSON, SSE, or 202 responses. Every other path
 * goes to the configured application route, or answers 404. Long-lived SSE responses
 * ({@code subscriptions/listen}) emit an SSE comment line whenever they have been idle
 * for the keep-alive interval.
 */
public final class HttpOverStdioServerTransport implements McpStatelessServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(HttpOverStdioServerTransport.class);

	/** Default MCP endpoint path. */
	public static final String DEFAULT_ENDPOINT = "/mcp";

	/** Default idle interval before a long-lived stream emits a keep-alive comment. */
	public static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);

	static final String KEEP_ALIVE_COMMENT = ":\n";

	private static final long DEFAULT_REQUEST_MAX_SIZE = 16L * 1024 * 1024;

	private final EventLoopGroup eventLoopGroup;

	private final boolean ownsEventLoopGroup;

	private final McpJsonMapper jsonMapper;

	private final long requestMaxSize;

	private final String endpoint;

	private final ServerHttpHeaderValidator securityValidator;

	private final McpTransportContextExtractor<Http2Request> contextExtractor;

	private final McpHttpBinding2026 binding;

	private final Http2RequestHandler applicationRoute;

	private final Duration keepAliveInterval;

	private final McpEndpointAuthorizer authorizer;

	private final AtomicBoolean closing = new AtomicBoolean();

	private final PipeHttp2Server server;

	/** Runs the close sequence at most once, on first subscription, and replays it. */
	private final Mono<Void> closeOnce = Mono.defer(this::closeSequence).cache();

	private volatile StatelessHttpDispatcher dispatcher;

	private HttpOverStdioServerTransport(Builder builder, DuplexByteChannel duplex) {
		this.ownsEventLoopGroup = builder.eventLoopGroup == null;
		this.eventLoopGroup = this.ownsEventLoopGroup ? new DefaultEventLoopGroup() : builder.eventLoopGroup;
		this.jsonMapper = builder.jsonMapper != null ? builder.jsonMapper : McpJsonDefaults.getMapper();
		this.requestMaxSize = builder.requestMaxSize;
		this.endpoint = builder.endpoint;
		this.securityValidator = builder.securityValidator != null ? builder.securityValidator
				: DefaultServerTransportSecurityValidator.builder().allowedHosts(builder.allowedAuthorities).build();
		this.contextExtractor = builder.contextExtractor;
		this.binding = builder.binding;
		this.applicationRoute = builder.applicationRoute;
		this.keepAliveInterval = builder.keepAliveInterval;
		this.authorizer = builder.authorizer;
		this.server = PipeHttp2Server.start(duplex, this.eventLoopGroup, builder.config, this.requestMaxSize,
				this::handle);
	}

	/**
	 * @param duplex byte channel carrying HTTP/2
	 * @return a builder
	 */
	public static Builder builder(DuplexByteChannel duplex) {
		return new Builder(duplex);
	}

	/** Serves a duplex channel with every default. */
	public static HttpOverStdioServerTransport start(DuplexByteChannel duplex) {
		return builder(duplex).build();
	}

	/** Serves a duplex channel on a caller-owned event loop group with defaults. */
	public static HttpOverStdioServerTransport start(DuplexByteChannel duplex, EventLoopGroup eventLoopGroup,
			PipeChannelConfig config, McpJsonMapper jsonMapper) {
		return builder(duplex).eventLoopGroup(eventLoopGroup).pipeConfig(config).jsonMapper(jsonMapper).build();
	}

	/** Serves a duplex channel on a transport-owned event loop group with defaults. */
	public static HttpOverStdioServerTransport start(DuplexByteChannel duplex, PipeChannelConfig config,
			McpJsonMapper jsonMapper) {
		return builder(duplex).pipeConfig(config).jsonMapper(jsonMapper).build();
	}

	/**
	 * Serves this process's own stdin and stdout with defaults; see
	 * {@link #builderOnCurrentProcessStdio()} for the stdout reservation.
	 * @param eventLoopGroup event loop group owned by the caller
	 * @param config pipe channel configuration, or {@code null} for defaults
	 * @param jsonMapper JSON mapper used by the dispatcher
	 * @return the started transport
	 */
	public static HttpOverStdioServerTransport startOnCurrentProcessStdio(EventLoopGroup eventLoopGroup,
			PipeChannelConfig config, McpJsonMapper jsonMapper) {
		return builderOnCurrentProcessStdio().eventLoopGroup(eventLoopGroup)
			.pipeConfig(config)
			.jsonMapper(jsonMapper)
			.build();
	}

	/**
	 * Serves this process's own stdin and stdout, reserving stdout for HTTP/2 frames.
	 *
	 * <p>
	 * The protocol bytes are written to the raw {@link java.io.FileDescriptor#out}
	 * descriptor. Anything else that writes to {@link System#out} -- a logging backend's
	 * default console appender, a stray {@code println} -- would interleave text with
	 * HTTP/2 frames and corrupt the connection, so {@code System.out} is redirected to
	 * {@code System.err} before the builder is returned. Call this before any other
	 * component caches a reference to {@code System.out}.
	 * @return a builder for this process's stdio
	 */
	public static Builder builderOnCurrentProcessStdio() {
		DuplexByteChannel stdio = ProcessPipeDuplexByteChannel.forCurrentProcess();
		System.setOut(System.err);
		return builder(stdio);
	}

	/**
	 * Installs the handler. Until a handler is installed every MCP request answers 503,
	 * the status the dispatcher also uses while the transport is closing.
	 */
	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		Assert.notNull(mcpHandler, "mcpHandler must not be null");
		setStreamingHandler(McpStatelessStreamingServerHandler.adapt(mcpHandler));
	}

	/**
	 * Installs a handler that may answer requests with request-scoped or long-lived
	 * ({@code subscriptions/listen}) SSE streams. {@link #setMcpHandler} installs a
	 * legacy single-response handler through the same path.
	 * @param handler the streaming handler
	 */
	public void setStreamingHandler(McpStatelessStreamingServerHandler handler) {
		Assert.notNull(handler, "handler must not be null");
		// The transport has already run the security validator for this request.
		this.dispatcher = new StatelessHttpDispatcher(this.jsonMapper, handler, this.requestMaxSize, this.closing::get,
				ServerHttpHeaderValidator.NOOP, this.binding);
	}

	@Override
	public List<String> protocolVersions() {
		return this.binding != null ? this.binding.supportedVersions()
				: McpStatelessServerTransport.super.protocolVersions();
	}

	public PipeHttp2Server server() {
		return this.server;
	}

	private void handle(Http2Request request, Http2ResponseWriter writer) throws Exception {
		NettyHeaderAccessor headers = new NettyHeaderAccessor(request.headers());
		// Origin and authority are validated for every incoming request, before route
		// selection, so neither application routes nor the not-yet-ready 503 bypass them.
		try {
			this.securityValidator.validate(headers);
		}
		catch (ServerTransportSecurityException exception) {
			// Same response the dispatcher produced for this rejection before validation
			// moved ahead of route selection.
			writeResponse(writer, new StatelessHttpResponse(exception.getStatusCode(), Map.of(),
					new StatelessHttpResponse.Json(exception.getMessage())), this.keepAliveInterval);
			return;
		}
		if (!this.endpoint.equals(pathOnly(request.path()))) {
			if (this.applicationRoute != null) {
				this.applicationRoute.handle(request, writer);
			}
			else {
				writeEmpty(writer, 404, Map.of());
			}
			return;
		}
		McpTransportContext extracted = this.contextExtractor.extract(request);
		McpTransportContext context = extracted == null ? McpTransportContext.EMPTY : extracted;
		// Authorization does not depend on whether a handler is installed yet, so a
		// protected endpoint challenges before it reports 503.
		if (this.authorizer != null) {
			Optional<StatelessHttpResponse> denied = this.authorizer.authorize(request, context);
			if (denied.isPresent()) {
				writeResponse(writer, denied.get(), this.keepAliveInterval);
				return;
			}
		}
		StatelessHttpDispatcher current = this.dispatcher;
		if (current == null) {
			writeEmpty(writer, 503, Map.of());
			return;
		}
		// The body is borrowed for this call only, so decode it before going
		// asynchronous.
		String body = request.body().toString(StandardCharsets.UTF_8);
		Optional<Long> contentLength = headers.getHeader("content-length")
			.stream()
			.findFirst()
			.flatMap(HttpOverStdioServerTransport::parseContentLength);
		StatelessHttpDispatcher.Request statelessRequest = new StatelessHttpDispatcher.Request(request.method(),
				request.path(), headers, contentLength, body, context);
		Duration keepAlive = this.keepAliveInterval;
		current.dispatch(statelessRequest)
			.subscribe(response -> writeResponse(writer, response, keepAlive), failure -> {
				logger.warn("Unexpected dispatcher failure", failure);
				writeFailure(writer);
			});
	}

	private static String pathOnly(String path) {
		int query = path.indexOf('?');
		return query < 0 ? path : path.substring(0, query);
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
	static void writeResponse(Http2ResponseWriter writer, StatelessHttpResponse response, Duration keepAlive) {
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
		Flux<String> frames = sse.events().map(HttpOverStdioServerTransport::encodeSse);
		if (sse.longLived()) {
			frames = withKeepAlive(frames, keepAlive);
		}
		Disposable subscription = frames.subscribe(
				frame -> writer.data(Unpooled.copiedBuffer(frame, StandardCharsets.UTF_8), false),
				failure -> writer.reset(Http2Error.INTERNAL_ERROR.code()), writer::end);
		writer.onCancelled(subscription::dispose);
	}

	/** Emits a keep-alive comment whenever the stream has been idle for one interval. */
	static Flux<String> withKeepAlive(Flux<String> frames, Duration interval) {
		return frames.publish(shared -> Flux.merge(shared,
				shared.startWith("")
					.switchMap(frame -> Flux.interval(interval, interval).map(tick -> KEEP_ALIVE_COMMENT))
					.takeUntilOther(shared.then())));
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

	private static String encodeSse(StatelessHttpResponse.SseEvent event) {
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
			.then(Mono.fromFuture(this.server::drainOutput).timeout(Duration.ofSeconds(5)).onErrorComplete())
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

	/** Configures and starts an {@link HttpOverStdioServerTransport}. */
	public static final class Builder {

		private final DuplexByteChannel duplex;

		private EventLoopGroup eventLoopGroup;

		private PipeChannelConfig config;

		private McpJsonMapper jsonMapper;

		private long requestMaxSize = DEFAULT_REQUEST_MAX_SIZE;

		private String endpoint = DEFAULT_ENDPOINT;

		private List<String> allowedAuthorities = List.of();

		private ServerHttpHeaderValidator securityValidator;

		private McpTransportContextExtractor<Http2Request> contextExtractor = request -> McpTransportContext.EMPTY;

		private McpHttpBinding2026 binding = McpHttpBinding2026.builder()
			.supportedVersions(List.of(McpHttpBinding2026.PROTOCOL_VERSION))
			.build();

		private Http2RequestHandler applicationRoute;

		private Duration keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL;

		private McpEndpointAuthorizer authorizer;

		/**
		 * Lets the child act as an OAuth protected resource: the authorizer may answer an
		 * MCP endpoint request with 401 and a {@code WWW-Authenticate} challenge before
		 * it is dispatched. Application routes are not affected.
		 */
		public Builder authorizer(McpEndpointAuthorizer authorizer) {
			this.authorizer = authorizer;
			return this;
		}

		private Builder(DuplexByteChannel duplex) {
			this.duplex = Objects.requireNonNull(duplex, "duplex");
		}

		/** Caller-owned event loop group; when unset the transport owns one. */
		public Builder eventLoopGroup(EventLoopGroup eventLoopGroup) {
			this.eventLoopGroup = eventLoopGroup;
			return this;
		}

		public Builder pipeConfig(PipeChannelConfig config) {
			this.config = config;
			return this;
		}

		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		public Builder requestMaxSize(long requestMaxSize) {
			Assert.isTrue(requestMaxSize > 0, "requestMaxSize must be positive");
			this.requestMaxSize = requestMaxSize;
			return this;
		}

		/** The MCP endpoint path, matched exactly against {@code :path} without query. */
		public Builder endpoint(String endpoint) {
			Assert.isTrue(endpoint != null && endpoint.startsWith("/"), "endpoint must start with '/'");
			this.endpoint = endpoint;
			return this;
		}

		/**
		 * Logical authorities ({@code :authority}, e.g. {@code child.example:8443}) this
		 * child serves, matched exactly. When set, any other authority answers 421. When
		 * unset the authority is not checked. A present {@code Origin} header is always
		 * validated by the default validator and answers 403 unless allowed by a custom
		 * {@link #securityValidator}.
		 */
		public Builder allowedAuthorities(List<String> authorities) {
			this.allowedAuthorities = List.copyOf(authorities);
			return this;
		}

		/** Replaces the default Origin and authority validator. */
		public Builder securityValidator(ServerHttpHeaderValidator securityValidator) {
			this.securityValidator = Objects.requireNonNull(securityValidator, "securityValidator");
			return this;
		}

		/** Extracts the per-request transport context handed to MCP handlers. */
		public Builder contextExtractor(McpTransportContextExtractor<Http2Request> contextExtractor) {
			this.contextExtractor = Objects.requireNonNull(contextExtractor, "contextExtractor");
			return this;
		}

		/**
		 * The 2026-07-28 request-metadata binding; defaults to 2026-07-28 only. Passing
		 * {@code null} disables it and accepts requests without 2026 headers, which is
		 * not 2026-07-28 Streamable HTTP conformant.
		 */
		public Builder binding(McpHttpBinding2026 binding) {
			this.binding = binding;
			return this;
		}

		/**
		 * Handles every request outside the MCP endpoint, such as protected-resource
		 * metadata. The request body is borrowed for the duration of the call. Without a
		 * route such requests answer 404.
		 */
		public Builder applicationRoute(Http2RequestHandler applicationRoute) {
			this.applicationRoute = applicationRoute;
			return this;
		}

		public Builder keepAliveInterval(Duration keepAliveInterval) {
			Assert.isTrue(keepAliveInterval != null && !keepAliveInterval.isZero() && !keepAliveInterval.isNegative(),
					"keepAliveInterval must be positive");
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		public HttpOverStdioServerTransport build() {
			return new HttpOverStdioServerTransport(this, this.duplex);
		}

	}

}
