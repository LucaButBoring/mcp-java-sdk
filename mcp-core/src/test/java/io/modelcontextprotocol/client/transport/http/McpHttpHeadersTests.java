/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpHttpHeadersTests {

	@Test
	void lookupIsCaseInsensitive() {
		McpHttpHeaders headers = McpHttpHeaders.builder().add("Content-Type", "application/json").build();

		assertThat(headers.firstValue("content-type")).contains("application/json");
		assertThat(headers.allValues("CONTENT-TYPE")).containsExactly("application/json");
	}

	@Test
	void retainsMultiValueInsertionOrderAcrossHeaderCasing() {
		McpHttpHeaders headers = McpHttpHeaders.builder()
			.add("Accept", "application/json")
			.add("accept", "text/event-stream")
			.build();

		assertThat(headers.allValues("ACCEPT")).containsExactly("application/json", "text/event-stream");
		assertThat(headers.map()).containsOnlyKeys("Accept");
	}

	@Test
	void copiesSourceAndExposesImmutableCollections() {
		List<String> sourceValues = new ArrayList<>(List.of("one"));
		McpHttpHeaders headers = McpHttpHeaders.of(Map.of("X-Test", sourceValues));
		sourceValues.add("two");

		assertThat(headers.allValues("x-test")).containsExactly("one");
		assertThatThrownBy(() -> headers.map().put("Other", List.of("value")))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> headers.allValues("x-test").add("value"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
