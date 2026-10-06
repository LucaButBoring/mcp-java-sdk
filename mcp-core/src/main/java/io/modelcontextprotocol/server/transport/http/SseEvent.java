/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.Objects;
import java.util.Optional;

/**
 * One Server-Sent Event. Front ends encode it as an optional {@code id: } line, an
 * {@code event: } line, a {@code data: } line, and a terminating blank line.
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
