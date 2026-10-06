/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.http.McpStatelessStreamingServerHandler;
import io.modelcontextprotocol.server.transport.http.SseEvent;
import io.modelcontextprotocol.server.transport.http.StatelessHttpDispatcher;
import io.modelcontextprotocol.server.transport.http.StatelessHttpResponse;
import io.modelcontextprotocol.server.transport.http.StatelessServerHandlerAdapter;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.util.Assert;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * Implementation of an HttpServlet based {@link McpStatelessServerTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@WebServlet(asyncSupported = true)
public class HttpServletStatelessServerTransport extends HttpServlet implements McpStatelessServerTransport {

	/**
	 * Default maximum size of a single request body: 16 MiB (16 * 1024 * 1024 bytes).
	 */
	private static final int DEFAULT_REQUEST_MAX_SIZE = 16 * 1024 * 1024;

	private static final Logger logger = LoggerFactory.getLogger(HttpServletStatelessServerTransport.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String TEXT_EVENT_STREAM = "text/event-stream";

	public static final String ACCEPT = "Accept";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	private final McpJsonMapper jsonMapper;

	private final String mcpEndpoint;

	private McpStatelessServerHandler mcpHandler;

	private McpStatelessStreamingServerHandler streamingHandler;

	private volatile StatelessHttpDispatcher dispatcher;

	private McpTransportContextExtractor<HttpServletRequest> contextExtractor;

	private volatile boolean isClosing = false;

	/**
	 * Security validator for validating HTTP requests.
	 */
	private final ServerHttpHeaderValidator httpHeaderValidator;

	/**
	 * Maximum size, in bytes, of a single request body accepted by this transport.
	 */
	private final int requestMaxSize;

	/**
	 * Constructs a new HttpServletStatelessServerTransport instance.
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization of
	 * messages.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages.
	 * @param contextExtractor The extractor for transport context from the request.
	 * @param httpHeaderValidator The HTTP header validator for validating HTTP requests.
	 * @param requestMaxSize The maximum size, in bytes, of a single request body. Must be
	 * positive.
	 * @throws IllegalArgumentException if any parameter is null
	 */
	private HttpServletStatelessServerTransport(McpJsonMapper jsonMapper, String mcpEndpoint,
			McpTransportContextExtractor<HttpServletRequest> contextExtractor,
			ServerHttpHeaderValidator httpHeaderValidator, int requestMaxSize) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "mcpEndpoint must not be null");
		Assert.notNull(contextExtractor, "contextExtractor must not be null");
		Assert.notNull(httpHeaderValidator, "HTTP header validator must not be null");
		Assert.isTrue(requestMaxSize > 0, "requestMaxSize must be positive");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.contextExtractor = contextExtractor;
		this.httpHeaderValidator = httpHeaderValidator;
		this.requestMaxSize = requestMaxSize;
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.mcpHandler = mcpHandler;
		this.streamingHandler = StatelessServerHandlerAdapter.adapt(mcpHandler);
		this.dispatcher = new StatelessHttpDispatcher(this.jsonMapper, this.streamingHandler, this.requestMaxSize,
				() -> this.isClosing, this.httpHeaderValidator);
	}

	void setStreamingHandler(McpStatelessStreamingServerHandler streamingHandler) {
		this.streamingHandler = streamingHandler;
		this.dispatcher = new StatelessHttpDispatcher(this.jsonMapper, streamingHandler, this.requestMaxSize,
				() -> this.isClosing, this.httpHeaderValidator);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> this.isClosing = true);
	}

	/**
	 * Handles GET requests - returns 405 METHOD NOT ALLOWED as stateless transport
	 * doesn't support GET requests.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	/**
	 * Handles POST requests for incoming JSON-RPC messages from clients.
	 * @param request The HTTP servlet request containing the JSON-RPC message
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		if (request.getContentLengthLong() > this.requestMaxSize) {
			response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			return;
		}

		try {
			this.httpHeaderValidator.validate(new HttpServletHeaderAccessor(request));
		}
		catch (ServerTransportSecurityException e) {
			response.sendError(e.getStatusCode(), e.getMessage());
			return;
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		String accept = request.getHeader(ACCEPT);
		if (accept == null || !(accept.contains(APPLICATION_JSON) && accept.contains(TEXT_EVENT_STREAM))) {
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					McpError.builder(io.modelcontextprotocol.spec.McpSchema.ErrorCodes.METHOD_NOT_FOUND)
						.message("Both application/json and text/event-stream required in Accept header")
						.build());
			return;
		}

		try {
			String body = HttpServletRequestUtils.readBody(request, this.requestMaxSize);
			StatelessHttpDispatcher currentDispatcher = this.dispatcher;
			if (currentDispatcher == null) {
				handleWithoutInstalledHandler(response, body, transportContext);
				return;
			}
			Optional<String> protocolVersion = Optional.ofNullable(request.getHeader(HttpHeaders.PROTOCOL_VERSION));
			StatelessHttpResponse httpResponse = currentDispatcher.handle(body, transportContext, protocolVersion)
				.block();
			writeResponse(response, httpResponse);
		}
		catch (MaxSizeExceededException e) {
			response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
		}
		catch (Exception e) {
			logger.error("Unexpected error handling message: {}", e.getMessage());
			this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					McpError.builder(io.modelcontextprotocol.spec.McpSchema.ErrorCodes.INTERNAL_ERROR)
						.message("Unexpected error: " + e.getMessage())
						.build());
		}
	}

	private void handleWithoutInstalledHandler(HttpServletResponse response, String body,
			McpTransportContext transportContext) throws IOException {
		McpSchema.JSONRPCMessage message;
		try {
			message = McpSchema.deserializeJsonRpcMessage(this.jsonMapper, body);
		}
		catch (IllegalArgumentException | IOException exception) {
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Invalid message format").build());
			return;
		}
		if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
			try {
				this.mcpHandler.handleRequest(transportContext, jsonrpcRequest)
					.contextWrite(context -> context.put(McpTransportContext.KEY, transportContext))
					.block();
			}
			catch (Exception exception) {
				this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Failed to handle request: " + exception.getMessage())
							.build());
			}
			return;
		}
		if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
			try {
				this.mcpHandler.handleNotification(transportContext, jsonrpcNotification)
					.contextWrite(context -> context.put(McpTransportContext.KEY, transportContext))
					.block();
			}
			catch (Exception exception) {
				this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Failed to handle notification: " + exception.getMessage())
							.build());
			}
			return;
		}
		this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
				McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
					.message("The server accepts either requests or notifications")
					.build());
	}

	private void writeResponse(HttpServletResponse response, StatelessHttpResponse httpResponse) throws IOException {
		response.setStatus(httpResponse.status());
		for (Map.Entry<String, List<String>> header : httpResponse.headers().entrySet()) {
			if (!"Content-Type".equalsIgnoreCase(header.getKey())) {
				for (String value : header.getValue()) {
					response.addHeader(header.getKey(), value);
				}
			}
		}
		if (httpResponse.body() instanceof StatelessHttpResponse.Empty) {
			return;
		}
		if (httpResponse.body() instanceof StatelessHttpResponse.Json json) {
			if (!httpResponse.headers().containsKey("Content-Type")) {
				response.sendError(httpResponse.status(), json.text());
				return;
			}
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			PrintWriter writer = response.getWriter();
			writer.write(json.text());
			writer.flush();
			return;
		}
		response.setContentType(TEXT_EVENT_STREAM);
		response.setCharacterEncoding(UTF_8);
		PrintWriter writer = response.getWriter();
		StatelessHttpResponse.Sse sse = (StatelessHttpResponse.Sse) httpResponse.body();
		// toStream() registers the subscription's cancel as the Stream's onClose hook, so
		// leaving this block for any reason (client disconnect, writer error, publisher
		// failure) cancels the upstream handler Flux. toIterable() offers no such hook.
		try (Stream<SseEvent> events = sse.events().toStream()) {
			Iterator<SseEvent> iterator = events.iterator();
			while (iterator.hasNext()) {
				SseEvent event = iterator.next();
				event.id().ifPresent(id -> writer.write("id: " + id + "\n"));
				writer.write("event: " + event.event() + "\n");
				writer.write("data: " + event.data() + "\n\n");
				writer.flush();
				if (writer.checkError()) {
					return;
				}
			}
		}
		catch (RuntimeException exception) {
			logger.error("Failed while writing SSE response: {}", exception.getMessage());
		}
	}

	/**
	 * Sends an error response to the client.
	 * @param response The HTTP servlet response
	 * @param httpCode The HTTP status code
	 * @param mcpError The MCP error to send
	 * @throws IOException If an I/O error occurs
	 */
	private void responseError(HttpServletResponse response, int httpCode, McpError mcpError) throws IOException {
		response.setContentType(APPLICATION_JSON);
		response.setCharacterEncoding(UTF_8);
		response.setStatus(httpCode);
		String jsonError = jsonMapper.writeValueAsString(mcpError);
		PrintWriter writer = response.getWriter();
		writer.write(jsonError);
		writer.flush();
	}

	/**
	 * Cleans up resources when the servlet is being destroyed.
	 * <p>
	 * This method ensures a graceful shutdown before calling the parent's destroy method.
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	/**
	 * Create a builder for the server.
	 * @return a fresh {@link Builder} instance.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link HttpServletStatelessServerTransport}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * HttpServletStatelessServerTransport with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<HttpServletRequest> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private ServerHttpHeaderValidator httpHeaderValidator = ServerHttpHeaderValidator.NOOP;

		private int requestMaxSize = DEFAULT_REQUEST_MAX_SIZE;

		private Builder() {
			// used by a static method
		}

		/**
		 * Sets the JsonMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param jsonMapper The JsonMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param messageEndpoint The message endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if messageEndpoint is null
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.notNull(messageEndpoint, "Message endpoint must not be null");
			this.mcpEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the context extractor that allows providing the MCP feature
		 * implementations to inspect HTTP transport level metadata that was present at
		 * HTTP request processing time. This allows to extract custom headers and other
		 * useful data for use during execution later on in the process.
		 * @param contextExtractor The contextExtractor to fill in a
		 * {@link McpTransportContext}.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<HttpServletRequest> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Sets the security validator for validating HTTP requests.
		 * @param securityValidator The security validator to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if securityValidator is null
		 * @deprecated Use {@link #httpHeaderValidator(ServerHttpHeaderValidator)}
		 * instead.
		 */
		@Deprecated
		public Builder securityValidator(ServerTransportSecurityValidator securityValidator) {
			Assert.notNull(securityValidator, "Security validator must not be null");
			this.httpHeaderValidator = ServerTransportSecurityValidator.toHttpHeaderValidator(securityValidator);
			return this;
		}

		/**
		 * Sets the HTTP header validator for validating HTTP requests.
		 * @param httpHeaderValidator The HTTP header validator to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if httpHeaderValidator is null
		 */
		public Builder httpHeaderValidator(ServerHttpHeaderValidator httpHeaderValidator) {
			Assert.notNull(httpHeaderValidator, "HTTP header validator must not be null");
			this.httpHeaderValidator = httpHeaderValidator;
			return this;
		}

		/**
		 * Sets the maximum size, in bytes, of a single request body accepted by this
		 * transport. Requests whose body exceeds this size are rejected with a 413
		 * (Payload Too Large) response. Defaults to 16 MiB if not set.
		 * @param requestMaxSize The maximum request body size, in bytes. Must be
		 * positive.
		 * @return this builder instance
		 */
		public Builder maxRequestSize(int requestMaxSize) {
			this.requestMaxSize = requestMaxSize;
			return this;
		}

		/**
		 * Builds a new instance of {@link HttpServletStatelessServerTransport} with the
		 * configured settings.
		 * @return A new HttpServletStatelessServerTransport instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public HttpServletStatelessServerTransport build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");
			return new HttpServletStatelessServerTransport(
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, mcpEndpoint, contextExtractor,
					httpHeaderValidator, requestMaxSize);
		}

	}

}
