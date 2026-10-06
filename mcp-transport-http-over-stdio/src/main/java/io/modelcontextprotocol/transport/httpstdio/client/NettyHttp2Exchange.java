package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.client.transport.http.McpHttpExchange;
import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.client.transport.http.McpHttpResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Client;
import io.modelcontextprotocol.transport.httpstdio.http2.StreamingResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * Adapts the transport-neutral {@link McpHttpExchange} seam to one HTTP/2 stream on a
 * {@link PipeHttp2Client}.
 *
 * <p>
 * The request URI supplies {@code :scheme}, {@code :authority} and {@code :path}. The
 * returned {@code Mono} completes when response headers arrive and fails if the stream
 * terminates first. Cancelling either the {@code Mono} or the response body subscription
 * resets only this stream; the connection stays usable.
 */
public final class NettyHttp2Exchange implements McpHttpExchange {

	/** Protocol version reported to the authorization metadata bridge. */
	public static final String PROTOCOL_VERSION = "HTTP/2";

	private final PipeHttp2Client client;

	public NettyHttp2Exchange(PipeHttp2Client client) {
		this.client = Objects.requireNonNull(client, "client");
	}

	@Override
	public Mono<McpHttpResponse> exchange(McpHttpRequest request, McpTransportContext context) {
		return Mono.create(responseSink -> {
			Sinks.Many<List<ByteBuffer>> bodySink = Sinks.many().unicast().onBackpressureBuffer();
			StreamingResponse[] stream = new StreamingResponse[1];
			Flux<List<ByteBuffer>> body = bodySink.asFlux().doOnCancel(() -> cancel(stream[0]));
			stream[0] = open(request, responseSink, bodySink, body);
			responseSink.onCancel(() -> cancel(stream[0]));
		});
	}

	private StreamingResponse open(McpHttpRequest request, MonoSink<McpHttpResponse> responseSink,
			Sinks.Many<List<ByteBuffer>> bodySink, Flux<List<ByteBuffer>> body) {
		URI uri = request.uri();
		Http2Headers headers = new DefaultHttp2Headers();
		request.headers().map().forEach((name, values) -> {
			if (!name.startsWith(":")) {
				String lower = name.toLowerCase(Locale.ROOT);
				values.forEach(value -> headers.add(lower, value));
			}
		});
		ByteBuf requestBody = request.body()
			.map(value -> Unpooled.copiedBuffer(value, StandardCharsets.UTF_8))
			.orElse(Unpooled.EMPTY_BUFFER);
		AtomicBoolean headersEmitted = new AtomicBoolean();

		StreamingResponse stream = this.client.requestStreaming(request.method(), scheme(uri), authority(uri),
				path(uri), headers, requestBody, new StreamingResponse.Listener() {
					@Override
					public void onHeaders(Http2Headers responseHeaders, boolean endStream) {
						if (headersEmitted.compareAndSet(false, true)) {
							responseSink
								.success(new McpHttpResponse(status(responseHeaders), toMcpHeaders(responseHeaders),
										JdkFlowAdapter.publisherToFlowPublisher(body), request, PROTOCOL_VERSION));
						}
						if (endStream) {
							bodySink.tryEmitComplete();
						}
					}

					@Override
					public void onData(ByteBuf data, boolean endStream) {
						try {
							byte[] bytes = new byte[data.readableBytes()];
							data.getBytes(data.readerIndex(), bytes);
							bodySink.tryEmitNext(List.of(ByteBuffer.wrap(bytes)));
						}
						finally {
							data.release();
						}
						if (endStream) {
							bodySink.tryEmitComplete();
						}
					}

					@Override
					public void onComplete() {
						bodySink.tryEmitComplete();
					}
				});

		// The stream writes a retained duplicate of the request body, so this exchange
		// still owns the original and releases it exactly once when the stream ends.
		// Every terminal path, including failure to open, completes this future.
		stream.completion().whenComplete((ignored, failure) -> {
			requestBody.release();
			if (failure != null) {
				Throwable cause = unwrap(failure);
				if (!headersEmitted.get()) {
					responseSink.error(cause);
				}
				bodySink.tryEmitError(cause);
			}
		});
		return stream;
	}

	private static String scheme(URI uri) {
		return uri.getScheme() == null ? PipeHttp2Client.DEFAULT_SCHEME : uri.getScheme().toLowerCase(Locale.ROOT);
	}

	private static String authority(URI uri) {
		return uri.getRawAuthority() == null ? PipeHttp2Client.DEFAULT_AUTHORITY : uri.getRawAuthority();
	}

	private static String path(URI uri) {
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
	}

	private static int status(Http2Headers headers) {
		CharSequence status = headers.status();
		if (status == null) {
			throw new IllegalStateException("HTTP/2 response headers have no :status");
		}
		return Integer.parseInt(status.toString());
	}

	private static Throwable unwrap(Throwable failure) {
		return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
	}

	private static void cancel(StreamingResponse response) {
		if (response != null) {
			response.cancel();
		}
	}

	private static McpHttpHeaders toMcpHeaders(Http2Headers headers) {
		Map<String, List<String>> values = new LinkedHashMap<>();
		for (Map.Entry<CharSequence, CharSequence> entry : headers) {
			String name = entry.getKey().toString();
			if (!name.startsWith(":")) {
				values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(entry.getValue().toString());
			}
		}
		return McpHttpHeaders.of(values);
	}

}
