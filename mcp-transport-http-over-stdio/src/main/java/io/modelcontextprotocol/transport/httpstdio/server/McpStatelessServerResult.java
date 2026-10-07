/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.Objects;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Flux;

/** Result of handling one stateless MCP message. */
public sealed interface McpStatelessServerResult
		permits McpStatelessServerResult.Accepted, McpStatelessServerResult.Single, McpStatelessServerResult.Stream {

	/** An accepted notification, represented by HTTP 202 with no response body. */
	record Accepted() implements McpStatelessServerResult {
	}

	/**
	 * A single JSON-RPC response serialized as one {@code application/json} object.
	 *
	 * @param response the response
	 */
	record Single(McpSchema.JSONRPCResponse response) implements McpStatelessServerResult {

		public Single {
			Objects.requireNonNull(response, "response must not be null");
		}
	}

	/**
	 * An ordered stream of messages produced for one request.
	 *
	 * <p>
	 * Request-scoped streams emit zero or more notifications followed by exactly one
	 * JSON-RPC response and then complete. Long-lived streams, used by
	 * {@code subscriptions/listen}, emit notifications, may end with a response, and may
	 * complete without a response or remain open until cancelled. Both policies reject
	 * JSON-RPC requests and messages emitted after a response. Cancelling
	 * {@link #messages} is the cancellation signal to the handler.
	 *
	 * @param messages the message stream
	 * @param longLived whether normal completion without a terminal response is allowed
	 */
	record Stream(Flux<McpSchema.JSONRPCMessage> messages, boolean longLived) implements McpStatelessServerResult {

		public Stream {
			Objects.requireNonNull(messages, "messages must not be null");
		}

		/**
		 * Creates a request-scoped stream that requires exactly one terminal response.
		 */
		public static Stream requestScoped(Flux<McpSchema.JSONRPCMessage> messages) {
			return new Stream(messages, false);
		}

		/** Creates a long-lived {@code subscriptions/listen} stream. */
		public static Stream longLived(Flux<McpSchema.JSONRPCMessage> messages) {
			return new Stream(messages, true);
		}
	}

}
