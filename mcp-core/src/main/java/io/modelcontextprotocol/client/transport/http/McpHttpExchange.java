/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import io.modelcontextprotocol.common.McpTransportContext;
import reactor.core.publisher.Mono;

/**
 * Executes transport-neutral HTTP requests.
 *
 * <p>
 * Implementations must not block the caller. Cancellation of the returned {@link Mono}
 * must release the underlying connection or stream.
 */
@FunctionalInterface
public interface McpHttpExchange {

	/**
	 * Execute an HTTP request.
	 * @param request request to execute
	 * @param context MCP transport context
	 * @return a non-blocking response publisher
	 */
	Mono<McpHttpResponse> exchange(McpHttpRequest request, McpTransportContext context);

}
