/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
	public record Sse(Flux<StatelessHttpResponse.SseEvent> events, boolean longLived) implements Body {

		public Sse(Flux<StatelessHttpResponse.SseEvent> events) {
			this(events, false);
		}

		public Sse {
			Objects.requireNonNull(events, "events must not be null");
		}
	}

	/**
	 * One Server-Sent Event, encoded as an optional {@code id: } line, an {@code event: }
	 * line, a {@code data: } line, and a terminating blank line.
	 *
	 * @param id optional event identifier
	 * @param event event type
	 * @param data serialized event data
	 */
	public record SseEvent(Optional<String> id, String event, String data) {

		public SseEvent {
			Objects.requireNonNull(id, "id must not be null");
			Objects.requireNonNull(event, "event must not be null");
			Objects.requireNonNull(data, "data must not be null");
		}

	}

}
