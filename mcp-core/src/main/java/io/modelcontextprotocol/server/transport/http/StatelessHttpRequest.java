/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.Objects;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HeaderAccessor;

/**
 * Container-neutral HTTP input for one stateless MCP dispatch.
 *
 * <p>
 * The body has already been read and decoded by the front end. Front ends that need an
 * exact byte limit must enforce it while reading; the dispatcher also enforces the
 * configured limit against the UTF-8 representation.
 *
 * @param method HTTP method
 * @param path request path
 * @param headers request headers
 * @param contentLength declared content length, when present
 * @param body decoded request body
 * @param transportContext transport context extracted by the front end
 */
public record StatelessHttpRequest(String method, String path, HeaderAccessor headers, Optional<Long> contentLength,
		String body, McpTransportContext transportContext) {

	public StatelessHttpRequest {
		Objects.requireNonNull(method, "method must not be null");
		Objects.requireNonNull(path, "path must not be null");
		Objects.requireNonNull(headers, "headers must not be null");
		Objects.requireNonNull(contentLength, "contentLength must not be null");
		Objects.requireNonNull(body, "body must not be null");
		Objects.requireNonNull(transportContext, "transportContext must not be null");
	}

}
