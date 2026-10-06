/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.Arrays;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class McpStatelessServerResultTests {

	@Test
	void resultIsSealedWithExactlyThreePermittedRecords() {
		assertThat(McpStatelessServerResult.class.isSealed()).isTrue();
		assertThat(Arrays.stream(McpStatelessServerResult.class.getPermittedSubclasses()).map(Class::getSimpleName))
			.containsExactlyInAnyOrder("Accepted", "Single", "Stream");
		assertThat(McpStatelessServerResult.Accepted.class.isRecord()).isTrue();
		assertThat(McpStatelessServerResult.Single.class.isRecord()).isTrue();
		assertThat(McpStatelessServerResult.Stream.class.isRecord()).isTrue();
	}

	@Test
	void resultPayloadsMustNotBeNull() {
		assertThatNullPointerException().isThrownBy(() -> new McpStatelessServerResult.Single(null));
		assertThatNullPointerException().isThrownBy(() -> new McpStatelessServerResult.Stream(null, false));
	}

	@Test
	void streamRetainsOrderedMessages() {
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", null);
		McpSchema.JSONRPCResponse response = McpSchema.JSONRPCResponse.result("1", "done");
		McpStatelessServerResult.Stream result = McpStatelessServerResult.Stream
			.requestScoped(Flux.just(notification, response));
		assertThat(result.messages().collectList().block()).containsExactly(notification, response);
		assertThat(result.longLived()).isFalse();
	}

	@Test
	void factoriesRecordTerminalResponsePolicy() {
		assertThat(McpStatelessServerResult.Stream.requestScoped(Flux.empty()).longLived()).isFalse();
		assertThat(McpStatelessServerResult.Stream.longLived(Flux.empty()).longLived()).isTrue();
	}

}
