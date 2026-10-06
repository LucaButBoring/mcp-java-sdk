/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class StatelessServerHandlerAdapterTests {

	@Test
	void requestMapsToSingleAndPropagatesTransportContext() {
		McpTransportContext transportContext = McpTransportContext.create(Map.of("tenant", "one"));
		AtomicReference<McpTransportContext> reactorContext = new AtomicReference<>();
		McpSchema.JSONRPCResponse expected = McpSchema.JSONRPCResponse.result("1", "ok");
		McpStatelessServerHandler handler = new TestHandler((context, request) -> Mono.deferContextual(view -> {
			reactorContext.set(view.get(McpTransportContext.KEY));
			return Mono.just(expected);
		}), (context, notification) -> Mono.empty());
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest("tools/list", "1");

		StepVerifier
			.create(StatelessServerHandlerAdapter.adapt(handler)
				.handle(new McpStatelessServerExchange(transportContext, request, Optional.empty())))
			.assertNext(result -> {
				assertThat(result).isEqualTo(new McpStatelessServerResult.Single(expected));
				assertThat(reactorContext.get()).isSameAs(transportContext);
			})
			.verifyComplete();
	}

	@Test
	void notificationMapsToAcceptedAndPropagatesTransportContext() {
		McpTransportContext transportContext = McpTransportContext.create(Map.of("tenant", "two"));
		AtomicReference<McpTransportContext> reactorContext = new AtomicReference<>();
		McpStatelessServerHandler handler = new TestHandler((context, request) -> Mono.empty(),
				(context, notification) -> Mono.deferContextual(view -> {
					reactorContext.set(view.get(McpTransportContext.KEY));
					return Mono.empty();
				}));
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/initialized", null);

		StepVerifier
			.create(StatelessServerHandlerAdapter.adapt(handler)
				.handle(new McpStatelessServerExchange(transportContext, notification, Optional.empty())))
			.expectNext(new McpStatelessServerResult.Accepted())
			.verifyComplete();
		assertThat(reactorContext.get()).isSameAs(transportContext);
	}

	@Test
	void otherMessageTypeFailsWithContractException() {
		McpStatelessServerHandler handler = new TestHandler((context, request) -> Mono.empty(),
				(context, notification) -> Mono.empty());
		McpSchema.JSONRPCResponse response = McpSchema.JSONRPCResponse.result("1", "unexpected");

		StepVerifier
			.create(StatelessServerHandlerAdapter.adapt(handler)
				.handle(new McpStatelessServerExchange(McpTransportContext.EMPTY, response, Optional.empty())))
			.expectErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpStatelessContractException.class)
					.hasMessage("The server accepts either requests or notifications");
				assertThat(((McpStatelessContractException) error).getCode())
					.isEqualTo(McpSchema.ErrorCodes.INVALID_REQUEST);
			})
			.verify();
	}

	private record TestHandler(RequestHandler requestHandler,
			NotificationHandler notificationHandler) implements McpStatelessServerHandler {

		@Override
		public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
				McpSchema.JSONRPCRequest request) {
			return this.requestHandler.handle(context, request);
		}

		@Override
		public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
			return this.notificationHandler.handle(context, notification);
		}
	}

	@FunctionalInterface
	private interface RequestHandler {

		Mono<McpSchema.JSONRPCResponse> handle(McpTransportContext context, McpSchema.JSONRPCRequest request);

	}

	@FunctionalInterface
	private interface NotificationHandler {

		Mono<Void> handle(McpTransportContext context, McpSchema.JSONRPCNotification notification);

	}

}
