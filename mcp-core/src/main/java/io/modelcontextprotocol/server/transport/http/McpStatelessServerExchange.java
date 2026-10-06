/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.Objects;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * A deserialized stateless MCP message and its transport metadata.
 *
 * @param transportContext transport-specific context extracted by the front end
 * @param message the already-deserialized JSON-RPC message
 * @param protocolVersionHeader the raw {@code MCP-Protocol-Version} header, carried
 * without validation
 */
public record McpStatelessServerExchange(McpTransportContext transportContext, McpSchema.JSONRPCMessage message,
		Optional<String> protocolVersionHeader) {

	public McpStatelessServerExchange {
		Objects.requireNonNull(transportContext, "transportContext must not be null");
		Objects.requireNonNull(message, "message must not be null");
		Objects.requireNonNull(protocolVersionHeader, "protocolVersionHeader must not be null");
	}

}
