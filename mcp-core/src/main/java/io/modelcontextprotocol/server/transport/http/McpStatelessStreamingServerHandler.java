/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import reactor.core.publisher.Mono;

/**
 * Handles stateless MCP exchanges that may produce request-scoped streams as defined by
 * the 2026-07-28 Streamable HTTP protocol.
 *
 * <p>
 * The existing {@code McpStatelessServerHandler} contract remains fully supported; it can
 * be adapted with {@link StatelessServerHandlerAdapter}.
 */
@FunctionalInterface
public interface McpStatelessStreamingServerHandler {

	/**
	 * Handles one already-deserialized MCP exchange without blocking.
	 * @param exchange the exchange
	 * @return the accepted, single-response, or streaming result
	 */
	Mono<McpStatelessServerResult> handle(McpStatelessServerExchange exchange);

}
