/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class StatelessHttpDispatcherTests {

	private static final McpJsonMapper JSON_MAPPER = io.modelcontextprotocol.json.McpJsonDefaults.getMapper();

	private static final McpSchema.JSONRPCRequest REQUEST = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
			"tools/list", "1", null);

	private static final McpSchema.JSONRPCNotification NOTIFICATION = new McpSchema.JSONRPCNotification(
			McpSchema.JSONRPC_VERSION, "notifications/initialized", null);

	private static final McpSchema.JSONRPCRequest LISTEN = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
			StatelessHttpDispatcher.LONG_LIVED_METHOD, "2", null);

	@Test
	void rejectsNonPostMethods() {
		for (String method : List.of("GET", "DELETE", "PUT")) {
			StatelessHttpResponse response = dispatcher(exchange -> Mono.error(new AssertionError("not called")))
				.dispatch(request(method, json(REQUEST)))
				.block();
			assertThat(response.status()).isEqualTo(405);
			assertThat(response.body()).isInstanceOf(StatelessHttpResponse.Empty.class);
		}
	}

	@Test
	void rejectsClosingServerBeforeOtherValidation() {
		StatelessHttpDispatcher dispatcher = new StatelessHttpDispatcher(JSON_MAPPER,
				exchange -> Mono.error(new AssertionError("not called")), 100, () -> true, headers -> {
					throw new AssertionError("validator not called");
				});
		StatelessHttpResponse response = dispatcher.dispatch(request("POST", "not json")).block();
		assertThat(response.status()).isEqualTo(503);
		assertThat(((StatelessHttpResponse.Json) response.body()).text()).isEqualTo("Server is shutting down");
	}

	@Test
	void rejectsDeclaredAndActualOversizeBodies() {
		StatelessHttpDispatcher dispatcher = dispatcher(exchange -> Mono.error(new AssertionError("not called")), 8);
		StatelessHttpDispatcher.Request declared = new StatelessHttpDispatcher.Request("POST", "/mcp", headers(),
				Optional.of(9L), "{}", McpTransportContext.EMPTY);
		assertThat(dispatcher.dispatch(declared).block().status()).isEqualTo(413);
		assertThat(dispatcher.dispatch(request("POST", "123456789")).block().status()).isEqualTo(413);
	}

	@Test
	void mapsSecurityValidatorStatusAndMessage() {
		ServerHttpHeaderValidator validator = headers -> {
			throw new ServerTransportSecurityException(403, "Denied");
		};
		StatelessHttpDispatcher dispatcher = new StatelessHttpDispatcher(JSON_MAPPER,
				exchange -> Mono.error(new AssertionError("not called")), 100, () -> false, validator);
		StatelessHttpResponse response = dispatcher.dispatch(request("POST", json(REQUEST))).block();
		assertThat(response.status()).isEqualTo(403);
		assertThat(((StatelessHttpResponse.Json) response.body()).text()).isEqualTo("Denied");
	}

	@Test
	void rejectsMissingRequiredAcceptMediaTypesWithLegacyError() {
		StatelessHttpDispatcher.Request request = new StatelessHttpDispatcher.Request("POST", "/mcp",
				new TestHeaders(Map.of("Accept", List.of("application/json"))), Optional.empty(), json(REQUEST),
				McpTransportContext.EMPTY);
		StatelessHttpResponse response = dispatcher(exchange -> Mono.error(new AssertionError("not called")))
			.dispatch(request)
			.block();
		assertJsonError(response, 400, McpSchema.ErrorCodes.METHOD_NOT_FOUND,
				"Both application/json and text/event-stream required in Accept header");
	}

	@Test
	void rejectsMalformedMessage() {
		StatelessHttpResponse response = dispatcher(exchange -> Mono.error(new AssertionError("not called")))
			.dispatch(request("POST", "{"))
			.block();
		assertJsonError(response, 400, McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid message format");
	}

	@Test
	void mapsAcceptedNotificationAndCarriesRawProtocolHeader() {
		StatelessHttpDispatcher.Request request = new StatelessHttpDispatcher.Request(
				"POST", "/mcp", new TestHeaders(Map.of("Accept", List.of("application/json, text/event-stream"),
						"MCP-Protocol-Version", List.of("raw-version"))),
				Optional.empty(), json(NOTIFICATION), McpTransportContext.EMPTY);
		StatelessHttpResponse response = dispatcher(exchange -> {
			assertThat(exchange.protocolVersionHeader()).contains("raw-version");
			return Mono.just(new McpStatelessServerResult.Accepted());
		}).dispatch(request).block();
		assertThat(response.status()).isEqualTo(202);
		assertThat(response.headers()).isEmpty();
		assertThat(response.body()).isInstanceOf(StatelessHttpResponse.Empty.class);
	}

	@Test
	void mapsSingleResponseToUtf8Json() {
		McpSchema.JSONRPCResponse expected = McpSchema.JSONRPCResponse.result("1", Map.of("tools", List.of()));
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(new McpStatelessServerResult.Single(expected)))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.headers()).containsEntry("Content-Type", List.of("application/json;charset=UTF-8"));
		assertThat(((StatelessHttpResponse.Json) response.body()).text()).isEqualTo(json(expected));
	}

	@Test
	void mapsTwoNotificationsThenResponseToSseWireFormat() {
		McpSchema.JSONRPCNotification first = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", Map.of("progress", 1));
		McpSchema.JSONRPCNotification second = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", Map.of("progress", 2));
		McpSchema.JSONRPCResponse terminal = McpSchema.JSONRPCResponse.result("1", "done");
		StatelessHttpResponse response = dispatcher(exchange -> Mono
			.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(first, second, terminal))))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.headers()).containsEntry("Content-Type", List.of("text/event-stream"))
			.containsEntry("Cache-Control", List.of("no-cache"))
			.containsEntry("X-Accel-Buffering", List.of("no"));
		List<StatelessHttpResponse.SseEvent> events = ((StatelessHttpResponse.Sse) response.body()).events()
			.collectList()
			.block();
		assertThat(encode(events)).isEqualTo("event: message\ndata: " + json(first) + "\n\n" + "event: message\ndata: "
				+ json(second) + "\n\n" + "event: message\ndata: " + json(terminal) + "\n\n");
	}

	@Test
	void longLivedStreamStaysOpenUntilCancelledAndPropagatesCancellation() {
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<McpSchema.JSONRPCMessage> longLived = Flux.create(sink -> sink.onCancel(() -> cancelled.set(true)));
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(longLived)))
			.dispatch(request("POST", json(LISTEN)))
			.block();
		StepVerifier.create(((StatelessHttpResponse.Sse) response.body()).events())
			.expectSubscription()
			.expectNoEvent(Duration.ofMillis(50))
			.thenCancel()
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void requestEmittedByHandlerFailsSseStream() {
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(REQUEST))))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		StepVerifier.create(((StatelessHttpResponse.Sse) response.body()).events())
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOf(StatelessHttpDispatcher.ContractViolation.class)
						.hasMessage("A stateless response stream must not emit a JSON-RPC request"))
			.verify();
	}

	@Test
	void messagePhaseDoesNotRepeatHttpPreflight() {
		AtomicBoolean closingChecked = new AtomicBoolean();
		AtomicBoolean headerValidated = new AtomicBoolean();
		StatelessHttpDispatcher dispatcher = new StatelessHttpDispatcher(JSON_MAPPER,
				exchange -> Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result("1", "ok"))),
				4096, () -> {
					closingChecked.set(true);
					return true;
				}, headers -> headerValidated.set(true));

		StatelessHttpResponse response = dispatcher.handle(json(REQUEST), McpTransportContext.EMPTY, Optional.empty())
			.block();

		assertThat(response.status()).isEqualTo(200);
		assertThat(closingChecked).isFalse();
		assertThat(headerValidated).isFalse();
	}

	@Test
	void rejectsInboundResponseBeforeHandlerInvocation() {
		AtomicBoolean called = new AtomicBoolean();
		McpSchema.JSONRPCResponse inbound = McpSchema.JSONRPCResponse.result("1", "unexpected");
		StatelessHttpResponse response = dispatcher(exchange -> {
			called.set(true);
			return Mono.just(new McpStatelessServerResult.Accepted());
		}).dispatch(request("POST", json(inbound))).block();

		assertJsonError(response, 400, McpSchema.ErrorCodes.INVALID_REQUEST,
				"The server accepts either requests or notifications");
		assertThat(called).isFalse();
	}

	@Test
	void synchronousAndNullHandlerFailuresUseLegacyRequestMessage() {
		StatelessHttpResponse synchronous = dispatcher(exchange -> {
			throw new IllegalStateException("sync boom");
		}).dispatch(request("POST", json(REQUEST))).block();
		StatelessHttpResponse nullResult = dispatcher(exchange -> null).dispatch(request("POST", json(REQUEST)))
			.block();

		assertJsonError(synchronous, 500, McpSchema.ErrorCodes.INTERNAL_ERROR, "Failed to handle request: sync boom");
		assertJsonError(nullResult, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle request: handler returned null");
	}

	@Test
	void synchronousAndNullHandlerFailuresUseLegacyNotificationMessage() {
		StatelessHttpResponse synchronous = dispatcher(exchange -> {
			throw new IllegalStateException("sync boom");
		}).dispatch(request("POST", json(NOTIFICATION))).block();
		StatelessHttpResponse nullResult = dispatcher(exchange -> null).dispatch(request("POST", json(NOTIFICATION)))
			.block();

		assertJsonError(synchronous, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle notification: sync boom");
		assertJsonError(nullResult, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle notification: handler returned null");
	}

	@Test
	void rejectsAcceptedForRequestAsHandlerViolation() {
		StatelessHttpResponse response = dispatcher(exchange -> Mono.just(new McpStatelessServerResult.Accepted()))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		assertJsonError(response, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle request: A request handler must return Single or Stream");
	}

	@Test
	void rejectsSingleAndStreamForNotificationAsHandlerViolations() {
		McpSchema.JSONRPCResponse terminal = McpSchema.JSONRPCResponse.result("1", "done");
		StatelessHttpResponse single = dispatcher(exchange -> Mono.just(new McpStatelessServerResult.Single(terminal)))
			.dispatch(request("POST", json(NOTIFICATION)))
			.block();
		StatelessHttpResponse stream = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(terminal))))
			.dispatch(request("POST", json(NOTIFICATION)))
			.block();

		assertJsonError(single, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle notification: A notification handler must return Accepted");
		assertJsonError(stream, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle notification: A notification handler must return Accepted");
	}

	@Test
	void requestScopedStreamMustEndWithResponse() {
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(NOTIFICATION))))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		StepVerifier.create(((StatelessHttpResponse.Sse) response.body()).events())
			.expectNextCount(1)
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOf(StatelessHttpDispatcher.ContractViolation.class)
						.hasMessage("A request-scoped response stream must emit a terminal JSON-RPC response"))
			.verify();
	}

	@Test
	void longLivedStreamMayCompleteWithoutResponse() {
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(NOTIFICATION))))
			.dispatch(request("POST", json(LISTEN)))
			.block();
		StepVerifier.create(((StatelessHttpResponse.Sse) response.body()).events()).expectNextCount(1).verifyComplete();
	}

	@Test
	void longLivedStreamRejectsRequestsAndPostResponseMessages() {
		McpSchema.JSONRPCResponse terminal = McpSchema.JSONRPCResponse.result("1", "done");
		StatelessHttpResponse requestEmission = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(REQUEST))))
			.dispatch(request("POST", json(LISTEN)))
			.block();
		StatelessHttpResponse postResponse = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(terminal, NOTIFICATION))))
			.dispatch(request("POST", json(LISTEN)))
			.block();

		StepVerifier.create(((StatelessHttpResponse.Sse) requestEmission.body()).events())
			.expectError(StatelessHttpDispatcher.ContractViolation.class)
			.verify();
		StepVerifier.create(((StatelessHttpResponse.Sse) postResponse.body()).events())
			.expectNextCount(1)
			.expectError(StatelessHttpDispatcher.ContractViolation.class)
			.verify();
	}

	@Test
	void mapsHandlerContractFailureToInvalidRequest() {
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.error(new StatelessHttpDispatcher.ContractViolation(123, "bad")))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		assertJsonError(response, 400, McpSchema.ErrorCodes.INVALID_REQUEST, "bad");
	}

	@Test
	void messagesAfterTerminalResponseFailSseStream() {
		McpSchema.JSONRPCResponse terminal = McpSchema.JSONRPCResponse.result("1", "done");
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(terminal, NOTIFICATION))))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		StepVerifier.create(((StatelessHttpResponse.Sse) response.body()).events())
			.expectNextCount(1)
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOf(StatelessHttpDispatcher.ContractViolation.class)
						.hasMessage("A stateless response stream must end after its JSON-RPC response"))
			.verify();
	}

	@Test
	void mapsRequestAndNotificationHandlerFailuresToLegacyMessages() {
		StatelessHttpDispatcher dispatcher = dispatcher(exchange -> Mono.error(new IllegalStateException("boom")));
		assertJsonError(dispatcher.dispatch(request("POST", json(REQUEST))).block(), 500,
				McpSchema.ErrorCodes.INTERNAL_ERROR, "Failed to handle request: boom");
		assertJsonError(dispatcher.dispatch(request("POST", json(NOTIFICATION))).block(), 500,
				McpSchema.ErrorCodes.INTERNAL_ERROR, "Failed to handle notification: boom");
	}

	@Test
	void longLivedStreamIsOnlyPermittedForSubscriptionsListen() {
		McpSchema.JSONRPCNotification progress = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", null);
		StatelessHttpResponse ordinary = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(progress))))
			.dispatch(request("POST", json(REQUEST)))
			.block();
		StatelessHttpResponse listen = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(progress))))
			.dispatch(request("POST", json(LISTEN)))
			.block();

		assertJsonError(ordinary, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle request: Only subscriptions/listen may return a long-lived response stream");
		assertThat(listen.status()).isEqualTo(200);
		assertThat(listen.body()).isInstanceOf(StatelessHttpResponse.Sse.class);
		StepVerifier.create(((StatelessHttpResponse.Sse) listen.body()).events()).expectNextCount(1).verifyComplete();
	}

	@Test
	void requestScopedStreamRemainsAvailableToSubscriptionsListen() {
		McpSchema.JSONRPCResponse terminal = McpSchema.JSONRPCResponse.result("2", "done");
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(terminal))))
			.dispatch(request("POST", json(LISTEN)))
			.block();
		assertThat(response.status()).isEqualTo(200);
		StepVerifier.create(((StatelessHttpResponse.Sse) response.body()).events()).expectNextCount(1).verifyComplete();
	}

	@Test
	void rejectsLongLivedStreamForNotificationAsHandlerViolation() {
		StatelessHttpResponse response = dispatcher(
				exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(Flux.just(NOTIFICATION))))
			.dispatch(request("POST", json(NOTIFICATION)))
			.block();
		assertJsonError(response, 500, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"Failed to handle notification: A notification handler must return Accepted");
	}

	private static StatelessHttpDispatcher dispatcher(McpStatelessStreamingServerHandler handler) {
		return dispatcher(handler, 4096);
	}

	private static StatelessHttpDispatcher dispatcher(McpStatelessStreamingServerHandler handler, long maxSize) {
		return new StatelessHttpDispatcher(JSON_MAPPER, handler, maxSize);
	}

	private static StatelessHttpDispatcher.Request request(String method, String body) {
		return new StatelessHttpDispatcher.Request(method, "/mcp", headers(), Optional.empty(), body,
				McpTransportContext.EMPTY);
	}

	private static TestHeaders headers() {
		return new TestHeaders(Map.of("Accept", List.of("application/json, text/event-stream")));
	}

	private static String json(Object value) {
		try {
			return JSON_MAPPER.writeValueAsString(value);
		}
		catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	private static void assertJsonError(StatelessHttpResponse response, int status, int code, String message) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.headers()).containsEntry("Content-Type", List.of("application/json;charset=UTF-8"));
		try {
			McpSchema.JSONRPCResponse.JSONRPCError error = JSON_MAPPER.readValue(
					((StatelessHttpResponse.Json) response.body()).text(),
					McpSchema.JSONRPCResponse.JSONRPCError.class);
			assertThat(error.code()).isEqualTo(code);
			assertThat(error.message()).isEqualTo(message);
		}
		catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private static String encode(List<StatelessHttpResponse.SseEvent> events) {
		StringBuilder wire = new StringBuilder();
		for (StatelessHttpResponse.SseEvent event : events) {
			event.id().ifPresent(id -> wire.append("id: ").append(id).append('\n'));
			wire.append("event: ").append(event.event()).append('\n');
			wire.append("data: ").append(event.data()).append("\n\n");
		}
		return wire.toString();
	}

	private record TestHeaders(Map<String, List<String>> values) implements HeaderAccessor {

		private TestHeaders {
			Map<String, List<String>> normalized = new LinkedHashMap<>();
			values.forEach((name, value) -> normalized.put(name.toLowerCase(), List.copyOf(value)));
			values = Map.copyOf(normalized);
		}

		@Override
		public List<String> getHeader(String name) {
			return this.values.getOrDefault(name.toLowerCase(), List.of());
		}

		@Override
		public List<String> getHeaderNames() {
			return new ArrayList<>(this.values.keySet());
		}
	}

}
