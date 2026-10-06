/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Adapts the established single-response stateless handler contract to the streaming
 * contract.
 */
public final class StatelessServerHandlerAdapter {

	private StatelessServerHandlerAdapter() {
	}

	/**
	 * Adapts a legacy stateless handler without changing its Reactor context behavior.
	 * @param handler the handler to adapt
	 * @return a streaming handler
	 */
	public static McpStatelessStreamingServerHandler adapt(McpStatelessServerHandler handler) {
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
			return Mono.error(new McpStatelessContractException(McpSchema.ErrorCodes.INVALID_REQUEST,
					"The server accepts either requests or notifications"));
		};
	}

}
