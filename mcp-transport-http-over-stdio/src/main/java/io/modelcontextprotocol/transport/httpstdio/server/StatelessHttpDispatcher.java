/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Nonblocking stateless Streamable HTTP dispatch for one MCP endpoint request: HTTP
 * preflight, handler invocation, and validation of the handler's result. GET, DELETE, and
 * all other non-POST methods are rejected with HTTP 405.
 */
final class StatelessHttpDispatcher {

	private static final Map<String, List<String>> NO_HEADERS = Map.of();

	private static final Map<String, List<String>> JSON_HEADERS = Map.of("Content-Type",
			List.of("application/json;charset=UTF-8"));

	private static final Map<String, List<String>> SSE_HEADERS = Map.of("Content-Type", List.of("text/event-stream"),
			"Cache-Control", List.of("no-cache"), "X-Accel-Buffering", List.of("no"));

	private static final String MESSAGE_KIND_ERROR = "The server accepts either requests or notifications";

	/**
	 * The only JSON-RPC request method whose response stream may stay open without a
	 * terminal response (MCP 2026-07-28, {@code subscriptions/listen}).
	 */
	public static final String LONG_LIVED_METHOD = "subscriptions/listen";

	private final McpJsonMapper jsonMapper;

	private final McpStatelessStreamingServerHandler handler;

	private final long requestMaxSize;

	private final BooleanSupplier closing;

	private final ServerHttpHeaderValidator headerValidator;

	/**
	 * Creates a dispatcher using the no-op HTTP security validator and an open server.
	 */
	public StatelessHttpDispatcher(McpJsonMapper jsonMapper, McpStatelessStreamingServerHandler handler,
			long requestMaxSize) {
		this(jsonMapper, handler, requestMaxSize, () -> false, ServerHttpHeaderValidator.NOOP);
	}

	/** Creates a dispatcher. */
	public StatelessHttpDispatcher(McpJsonMapper jsonMapper, McpStatelessStreamingServerHandler handler,
			long requestMaxSize, BooleanSupplier closing, ServerHttpHeaderValidator headerValidator) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(handler, "handler must not be null");
		Assert.isTrue(requestMaxSize > 0, "requestMaxSize must be positive");
		Assert.notNull(closing, "closing must not be null");
		Assert.notNull(headerValidator, "headerValidator must not be null");
		this.jsonMapper = jsonMapper;
		this.handler = handler;
		this.requestMaxSize = requestMaxSize;
		this.closing = closing;
		this.headerValidator = headerValidator;
	}

	/**
	 * Runs the complete HTTP preflight and message pipeline once without blocking.
	 * @param request neutral HTTP request
	 * @return the response
	 */
	public Mono<StatelessHttpResponse> dispatch(Request request) {
		return Mono.defer(() -> dispatchNow(request));
	}

	/**
	 * Runs only the message phase after a front end has admitted and read the request.
	 * @param body decoded request body
	 * @param transportContext transport context extracted by the front end
	 * @param protocolVersionHeader raw MCP protocol version header
	 * @return the response
	 */
	public Mono<StatelessHttpResponse> handle(String body, McpTransportContext transportContext,
			Optional<String> protocolVersionHeader) {
		Assert.notNull(body, "body must not be null");
		Assert.notNull(transportContext, "transportContext must not be null");
		Assert.notNull(protocolVersionHeader, "protocolVersionHeader must not be null");
		return Mono.defer(() -> handleNow(body, transportContext, protocolVersionHeader));
	}

	private Mono<StatelessHttpResponse> dispatchNow(Request request) {
		if (!"POST".equalsIgnoreCase(request.method())) {
			return Mono.just(empty(405));
		}
		if (this.closing.getAsBoolean()) {
			return Mono.just(text(503, "Server is shutting down"));
		}
		if (request.contentLength().filter(length -> length > this.requestMaxSize).isPresent()) {
			return Mono.just(empty(413));
		}
		try {
			this.headerValidator.validate(request.headers());
		}
		catch (ServerTransportSecurityException exception) {
			return Mono.just(text(exception.getStatusCode(), exception.getMessage()));
		}
		String accept = firstHeader(request, HttpHeaders.ACCEPT).orElse(null);
		if (accept == null || !(accept.contains("application/json") && accept.contains("text/event-stream"))) {
			return error(400, McpSchema.ErrorCodes.METHOD_NOT_FOUND,
					"Both application/json and text/event-stream required in Accept header");
		}
		if (request.body().getBytes(StandardCharsets.UTF_8).length > this.requestMaxSize) {
			return Mono.just(empty(413));
		}
		return handle(request.body(), request.transportContext(), firstHeader(request, HttpHeaders.PROTOCOL_VERSION));
	}

	private Mono<StatelessHttpResponse> handleNow(String body, McpTransportContext transportContext,
			Optional<String> protocolVersionHeader) {
		McpSchema.JSONRPCMessage message;
		try {
			message = McpSchema.deserializeJsonRpcMessage(this.jsonMapper, body);
		}
		catch (IllegalArgumentException | IOException exception) {
			return error(400, McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid message format");
		}
		if (!(message instanceof McpSchema.JSONRPCRequest) && !(message instanceof McpSchema.JSONRPCNotification)) {
			return error(400, McpSchema.ErrorCodes.INVALID_REQUEST, MESSAGE_KIND_ERROR);
		}
		return invoke(message, transportContext, protocolVersionHeader);
	}

	private Mono<StatelessHttpResponse> invoke(McpSchema.JSONRPCMessage message, McpTransportContext transportContext,
			Optional<String> protocolVersionHeader) {
		McpStatelessStreamingServerHandler.Exchange exchange = new McpStatelessStreamingServerHandler.Exchange(
				transportContext, message, protocolVersionHeader);
		String kind = message instanceof McpSchema.JSONRPCNotification ? "notification" : "request";
		return Mono.defer(() -> {
			Mono<McpStatelessServerResult> result = this.handler.handle(exchange);
			return result == null ? Mono.error(new NullPointerException("handler returned null")) : result;
		})
			.switchIfEmpty(Mono.error(new NullPointerException("handler completed without a result")))
			.flatMap(result -> mapResult(message, result))
			.onErrorResume(ContractViolation.class,
					exception -> exception.isHandlerViolation() ? handlerError(kind, exception.getMessage())
							: error(400, McpSchema.ErrorCodes.INVALID_REQUEST, exception.getMessage()))
			.onErrorResume(exception -> handlerError(kind, exception.getMessage()));
	}

	private Mono<StatelessHttpResponse> mapResult(McpSchema.JSONRPCMessage inbound, McpStatelessServerResult result) {
		if (inbound instanceof McpSchema.JSONRPCNotification) {
			if (result instanceof McpStatelessServerResult.Accepted) {
				return Mono.just(empty(202));
			}
			return Mono.error(handlerViolation("A notification handler must return Accepted"));
		}
		if (result instanceof McpStatelessServerResult.Accepted) {
			return Mono.error(handlerViolation("A request handler must return Single or Stream"));
		}
		if (result instanceof McpStatelessServerResult.Single single) {
			try {
				return Mono.just(new StatelessHttpResponse(200, JSON_HEADERS,
						new StatelessHttpResponse.Json(this.jsonMapper.writeValueAsString(single.response()))));
			}
			catch (IOException exception) {
				return Mono.error(exception);
			}
		}
		McpStatelessServerResult.Stream stream = (McpStatelessServerResult.Stream) result;
		if (stream.longLived() && !LONG_LIVED_METHOD.equals(((McpSchema.JSONRPCRequest) inbound).method())) {
			return Mono
				.error(handlerViolation("Only " + LONG_LIVED_METHOD + " may return a long-lived response stream"));
		}
		return Mono.just(new StatelessHttpResponse(200, SSE_HEADERS,
				new StatelessHttpResponse.Sse(validatedEvents(stream), stream.longLived())));
	}

	private Flux<StatelessHttpResponse.SseEvent> validatedEvents(McpStatelessServerResult.Stream stream) {
		return Flux.defer(() -> {
			AtomicBoolean responseEmitted = new AtomicBoolean();
			Flux<StatelessHttpResponse.SseEvent> events = stream.messages().handle((message, sink) -> {
				if (message instanceof McpSchema.JSONRPCRequest) {
					sink.error(handlerViolation("A stateless response stream must not emit a JSON-RPC request"));
					return;
				}
				if (responseEmitted.get()) {
					sink.error(handlerViolation("A stateless response stream must end after its JSON-RPC response"));
					return;
				}
				if (!(message instanceof McpSchema.JSONRPCNotification)
						&& !(message instanceof McpSchema.JSONRPCResponse)) {
					sink.error(
							handlerViolation("A stateless response stream may emit only notifications and a response"));
					return;
				}
				if (message instanceof McpSchema.JSONRPCResponse) {
					responseEmitted.set(true);
				}
				try {
					sink.next(new StatelessHttpResponse.SseEvent(Optional.empty(), "message",
							this.jsonMapper.writeValueAsString(message)));
				}
				catch (IOException exception) {
					sink.error(exception);
				}
			});
			return events.concatWith(Mono.defer(() -> !stream.longLived() && !responseEmitted.get()
					? Mono.error(
							handlerViolation("A request-scoped response stream must emit a terminal JSON-RPC response"))
					: Mono.empty()));
		});
	}

	private static ContractViolation handlerViolation(String message) {
		return new ContractViolation(McpSchema.ErrorCodes.INTERNAL_ERROR, message, true);
	}

	private Mono<StatelessHttpResponse> handlerError(String kind, String message) {
		return error(500, McpSchema.ErrorCodes.INTERNAL_ERROR, "Failed to handle " + kind + ": " + message);
	}

	private Optional<String> firstHeader(Request request, String name) {
		return request.headers().getHeader(name).stream().findFirst();
	}

	private Mono<StatelessHttpResponse> error(int status, int code, String message) {
		try {
			McpSchema.JSONRPCResponse.JSONRPCError error = new McpSchema.JSONRPCResponse.JSONRPCError(code, message,
					null);
			return Mono.just(new StatelessHttpResponse(status, JSON_HEADERS,
					new StatelessHttpResponse.Json(this.jsonMapper.writeValueAsString(error))));
		}
		catch (IOException exception) {
			return Mono.error(exception);
		}
	}

	private static StatelessHttpResponse empty(int status) {
		return new StatelessHttpResponse(status, NO_HEADERS, new StatelessHttpResponse.Empty());
	}

	private static StatelessHttpResponse text(int status, String message) {
		return new StatelessHttpResponse(status, NO_HEADERS, new StatelessHttpResponse.Json(message));
	}

	/**
	 * HTTP input for one dispatch. The body has already been read and decoded; the
	 * dispatcher enforces the size limit against its UTF-8 representation.
	 */
	record Request(String method, String path, HeaderAccessor headers, Optional<Long> contentLength, String body,
			McpTransportContext transportContext) {

		Request {
			Objects.requireNonNull(method, "method must not be null");
			Objects.requireNonNull(path, "path must not be null");
			Objects.requireNonNull(headers, "headers must not be null");
			Objects.requireNonNull(contentLength, "contentLength must not be null");
			Objects.requireNonNull(body, "body must not be null");
			Objects.requireNonNull(transportContext, "transportContext must not be null");
		}

	}

	/**
	 * A message-contract violation. Client violations answer HTTP 400; handler violations
	 * answer 500 before headers are committed, or fail a started SSE stream.
	 */
	static final class ContractViolation extends RuntimeException {

		private final int code;

		private final boolean handler;

		ContractViolation(int code, String message) {
			this(code, message, false);
		}

		ContractViolation(int code, String message, boolean handler) {
			super(message);
			this.code = code;
			this.handler = handler;
		}

		int getCode() {
			return this.code;
		}

		boolean isHandlerViolation() {
			return this.handler;
		}

	}

}
