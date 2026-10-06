/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import reactor.core.publisher.Flux;

/**
 * Container-neutral HTTP response from stateless MCP dispatch.
 *
 * @param status HTTP status
 * @param headers response headers
 * @param body response body
 */
public record StatelessHttpResponse(int status, Map<String, List<String>> headers, Body body) {

	public StatelessHttpResponse {
		headers = Map.copyOf(headers);
		Objects.requireNonNull(body, "body must not be null");
	}

	/** Response body variants. */
	public sealed interface Body permits Empty, Json, Sse {

	}

	/** An empty response body. */
	public record Empty() implements Body {
	}

	/**
	 * A serialized JSON response body.
	 *
	 * @param text serialized JSON
	 */
	public record Json(String text) implements Body {

		public Json {
			Objects.requireNonNull(text, "text must not be null");
		}
	}

	/**
	 * A stream of Server-Sent Events.
	 *
	 * @param events ordered events
	 */
	public record Sse(Flux<SseEvent> events) implements Body {

		public Sse {
			Objects.requireNonNull(events, "events must not be null");
		}
	}

}
