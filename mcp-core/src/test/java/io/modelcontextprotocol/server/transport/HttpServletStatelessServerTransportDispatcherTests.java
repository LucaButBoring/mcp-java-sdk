/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.transport.http.McpStatelessServerResult;
import io.modelcontextprotocol.server.transport.http.TestMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpServletStatelessServerTransportDispatcherTests {

	private static final McpJsonMapper JSON_MAPPER = new TestMcpJsonMapper();

	@Test
	void requestProducesByteIdenticalJsonResponse() throws Exception {
		McpSchema.JSONRPCResponse expected = McpSchema.JSONRPCResponse.result("1", Map.of("tools", List.of()));
		HttpServletStatelessServerTransport transport = transport(4096);
		transport.setMcpHandler(handler(Mono.just(expected), Mono.empty()));
		ResponseCapture capture = new ResponseCapture();

		transport.service(request(json(new McpSchema.JSONRPCRequest("tools/list", "1")), validAccept()),
				capture.response());

		verify(capture.response()).setStatus(200);
		verify(capture.response()).setContentType("application/json");
		verify(capture.response()).setCharacterEncoding("UTF-8");
		assertThat(capture.body()).isEqualTo(json(expected));
	}

	@Test
	void notificationProducesAcceptedWithNoBody() throws Exception {
		HttpServletStatelessServerTransport transport = transport(4096);
		transport.setMcpHandler(handler(Mono.empty(), Mono.empty()));
		ResponseCapture capture = new ResponseCapture();

		transport.service(request(
				json(new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "notifications/initialized", null)),
				validAccept()), capture.response());

		verify(capture.response()).setStatus(202);
		assertThat(capture.body()).isEmpty();
	}

	@Test
	void badAcceptProducesExactLegacyError() throws Exception {
		HttpServletStatelessServerTransport transport = transport(4096);
		transport.setMcpHandler(handler(Mono.empty(), Mono.empty()));
		ResponseCapture capture = new ResponseCapture();

		transport.service(request(json(new McpSchema.JSONRPCRequest("tools/list", "1")), "application/json"),
				capture.response());

		verify(capture.response()).setStatus(400);
		McpSchema.JSONRPCResponse.JSONRPCError error = JSON_MAPPER.readValue(capture.body(),
				McpSchema.JSONRPCResponse.JSONRPCError.class);
		assertThat(error.code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		assertThat(error.message()).isEqualTo("Both application/json and text/event-stream required in Accept header");
	}

	@Test
	void declaredOversizeRequestProduces413() throws Exception {
		HttpServletStatelessServerTransport transport = transport(8);
		HttpServletRequest request = request("{}", validAccept());
		when(request.getContentLengthLong()).thenReturn(9L);
		ResponseCapture capture = new ResponseCapture();

		transport.service(request, capture.response());

		verify(capture.response()).sendError(413);
	}

	@Test
	void streamingHandlerProducesRepositorySseWireFormat() throws Exception {
		McpSchema.JSONRPCNotification first = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", Map.of("progress", 1));
		McpSchema.JSONRPCNotification second = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", Map.of("progress", 2));
		McpSchema.JSONRPCResponse terminal = McpSchema.JSONRPCResponse.result("1", "done");
		HttpServletStatelessServerTransport transport = transport(4096);
		transport.setStreamingHandler(exchange -> Mono
			.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(first, second, terminal))));
		ResponseCapture capture = new ResponseCapture();

		transport.service(request(json(new McpSchema.JSONRPCRequest("tools/list", "1")), validAccept()),
				capture.response());

		verify(capture.response()).setStatus(200);
		verify(capture.response()).setContentType("text/event-stream");
		assertThat(capture.body()).isEqualTo("event: message\ndata: " + json(first) + "\n\n" + "event: message\ndata: "
				+ json(second) + "\n\n" + "event: message\ndata: " + json(terminal) + "\n\n");
	}

	@Test
	void servletRunsPreflightOnceAndDoesNotReadmitAfterConcurrentClose() throws Exception {
		AtomicInteger validations = new AtomicInteger();
		AtomicReference<HttpServletStatelessServerTransport> reference = new AtomicReference<>();
		HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
			.jsonMapper(JSON_MAPPER)
			.messageEndpoint("/mcp")
			.maxRequestSize(4096)
			.httpHeaderValidator(headers -> validations.incrementAndGet())
			.contextExtractor(request -> {
				reference.get().closeGracefully().block();
				return McpTransportContext.EMPTY;
			})
			.build();
		reference.set(transport);
		transport.setMcpHandler(handler(Mono.just(McpSchema.JSONRPCResponse.result("1", "ok")), Mono.empty()));
		ResponseCapture capture = new ResponseCapture();

		transport.service(request(json(new McpSchema.JSONRPCRequest("tools/list", "1")), validAccept()),
				capture.response());

		verify(capture.response()).setStatus(200);
		assertThat(validations).hasValue(1);
	}

	@Test
	void synchronousAndNullStreamingFailuresDoNotReachUnexpectedErrorPath() throws Exception {
		for (boolean nullHandler : List.of(false, true)) {
			HttpServletStatelessServerTransport transport = transport(4096);
			transport.setStreamingHandler(exchange -> {
				if (nullHandler) {
					return null;
				}
				throw new IllegalStateException("sync boom");
			});
			ResponseCapture capture = new ResponseCapture();

			transport.service(request(json(new McpSchema.JSONRPCRequest("tools/list", "1")), validAccept()),
					capture.response());

			McpSchema.JSONRPCResponse.JSONRPCError error = JSON_MAPPER.readValue(capture.body(),
					McpSchema.JSONRPCResponse.JSONRPCError.class);
			assertThat(error.message())
				.isEqualTo("Failed to handle request: " + (nullHandler ? "handler returned null" : "sync boom"));
			assertThat(error.message()).doesNotContain("Unexpected error");
		}
	}

	@Test
	void sseFailureAfterHeadersDoesNotAppendJsonError() throws Exception {
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", null);
		HttpServletStatelessServerTransport transport = transport(4096);
		transport.setStreamingHandler(
				exchange -> Mono.just(McpStatelessServerResult.Stream.requestScoped(Flux.just(notification))));
		ResponseCapture capture = new ResponseCapture();

		transport.service(request(json(new McpSchema.JSONRPCRequest("tools/list", "1")), validAccept()),
				capture.response());

		verify(capture.response()).setStatus(200);
		assertThat(capture.body()).doesNotContain("Unexpected error");
		assertThat(capture.body()).doesNotContain("Failed to handle request");
	}

	@Test
	void clientWriteFailureCancelsLongLivedUpstreamStream() throws Exception {
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/progress", null);
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<McpSchema.JSONRPCMessage> upstream = Flux.<McpSchema.JSONRPCMessage>create(sink -> {
			sink.onCancel(() -> cancelled.set(true));
			sink.next(notification);
			// never completes: a long-lived subscriptions/listen stream
		});
		HttpServletStatelessServerTransport transport = transport(4096);
		transport.setStreamingHandler(exchange -> Mono.just(McpStatelessServerResult.Stream.longLived(upstream)));

		// A writer whose underlying Writer fails on the first write: PrintWriter swallows
		// the
		// IOException and reports it through checkError(), exactly like a disconnected
		// client.
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(new Writer() {
			@Override
			public void write(char[] cbuf, int off, int len) throws IOException {
				throw new IOException("client went away");
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		}));

		transport.service(request(json(new McpSchema.JSONRPCRequest("subscriptions/listen", "1")), validAccept()),
				response);

		verify(response).setStatus(200);
		assertThat(cancelled).as("servlet must cancel the handler Flux when the client write fails").isTrue();
	}

	private static HttpServletStatelessServerTransport transport(int maxSize) {
		return HttpServletStatelessServerTransport.builder()
			.jsonMapper(JSON_MAPPER)
			.messageEndpoint("/mcp")
			.maxRequestSize(maxSize)
			.build();
	}

	private static McpStatelessServerHandler handler(Mono<McpSchema.JSONRPCResponse> requestResult,
			Mono<Void> notificationResult) {
		return new McpStatelessServerHandler() {
			@Override
			public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
					McpSchema.JSONRPCRequest request) {
				return requestResult;
			}

			@Override
			public Mono<Void> handleNotification(McpTransportContext transportContext,
					McpSchema.JSONRPCNotification notification) {
				return notificationResult;
			}
		};
	}

	private static HttpServletRequest request(String body, String accept) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getContentLengthLong()).thenReturn((long) body.getBytes(StandardCharsets.UTF_8).length);
		when(request.getHeader("Accept")).thenReturn(accept);
		when(request.getHeaders("Accept")).thenReturn(Collections.enumeration(List.of(accept)));
		when(request.getHeaders(anyString())).thenAnswer(invocation -> {
			String name = invocation.getArgument(0);
			return "Accept".equalsIgnoreCase(name) ? Collections.enumeration(List.of(accept))
					: Collections.emptyEnumeration();
		});
		when(request.getHeaderNames()).thenReturn(Collections.enumeration(List.of("Accept")));
		when(request.getInputStream()).thenReturn(servletInputStream(body.getBytes(StandardCharsets.UTF_8)));
		return request;
	}

	private static ServletInputStream servletInputStream(byte[] bytes) {
		ByteArrayInputStream delegate = new ByteArrayInputStream(bytes);
		return new ServletInputStream() {
			@Override
			public boolean isFinished() {
				return delegate.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}

			@Override
			public int read() {
				return delegate.read();
			}

			@Override
			public int read(byte[] bytes, int offset, int length) {
				return delegate.read(bytes, offset, length);
			}
		};
	}

	private static String validAccept() {
		return "application/json, text/event-stream";
	}

	private static String json(Object value) {
		try {
			return JSON_MAPPER.writeValueAsString(value);
		}
		catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	private static final class ResponseCapture {

		private final StringWriter body = new StringWriter();

		private final HttpServletResponse response = mock(HttpServletResponse.class);

		private ResponseCapture() throws Exception {
			when(this.response.getWriter()).thenReturn(new PrintWriter(this.body));
		}

		private HttpServletResponse response() {
			return this.response;
		}

		private String body() {
			return this.body.toString();
		}

	}

}
