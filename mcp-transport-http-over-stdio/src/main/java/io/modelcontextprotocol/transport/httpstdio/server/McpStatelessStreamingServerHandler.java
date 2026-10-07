/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.Objects;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Handles stateless MCP exchanges that may produce request-scoped streams as defined by
 * the 2026-07-28 Streamable HTTP protocol.
 *
 * <p>
 * The SDK's existing single-response {@link McpStatelessServerHandler} is adapted with
 * {@link #adapt(McpStatelessServerHandler)}.
 */
@FunctionalInterface
public interface McpStatelessStreamingServerHandler {

	/**
	 * Handles one already-deserialized MCP exchange without blocking.
	 * @param exchange the exchange
	 * @return the accepted, single-response, or streaming result
	 */
	Mono<McpStatelessServerResult> handle(Exchange exchange);

	/**
	 * Adapts a single-response stateless handler without changing its Reactor context
	 * behavior.
	 * @param handler the handler to adapt
	 * @return a streaming handler
	 */
	static McpStatelessStreamingServerHandler adapt(McpStatelessServerHandler handler) {
		Objects.requireNonNull(handler, "handler must not be null");
		return exchange -> {
			McpTransportContext transportContext = exchange.transportContext();
			McpSchema.JSONRPCMessage message = exchange.message();
			if (message instanceof McpSchema.JSONRPCRequest request) {
				return handler.handleRequest(transportContext, request)
					.<McpStatelessServerResult>map(McpStatelessServerResult.Single::new)
					.contextWrite(context -> context.put(McpTransportContext.KEY, transportContext));
			}
			if (message instanceof McpSchema.JSONRPCNotification notification) {
				return handler.handleNotification(transportContext, notification)
					.thenReturn((McpStatelessServerResult) new McpStatelessServerResult.Accepted())
					.contextWrite(context -> context.put(McpTransportContext.KEY, transportContext));
			}
			return Mono.error(new IllegalArgumentException("The server accepts either requests or notifications"));
		};
	}

	/**
	 * A deserialized stateless MCP message and its transport metadata.
	 *
	 * @param transportContext transport-specific context extracted by the front end
	 * @param message the already-deserialized JSON-RPC message
	 * @param protocolVersionHeader the raw {@code MCP-Protocol-Version} header, carried
	 * without validation
	 */
	record Exchange(McpTransportContext transportContext, McpSchema.JSONRPCMessage message,
			Optional<String> protocolVersionHeader) {

		public Exchange {
			Objects.requireNonNull(transportContext, "transportContext must not be null");
			Objects.requireNonNull(message, "message must not be null");
			Objects.requireNonNull(protocolVersionHeader, "protocolVersionHeader must not be null");
		}

	}

}
