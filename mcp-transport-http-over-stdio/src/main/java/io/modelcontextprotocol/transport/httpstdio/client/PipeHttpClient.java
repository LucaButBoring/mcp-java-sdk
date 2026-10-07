package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSession;

import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Client;
import io.modelcontextprotocol.transport.httpstdio.http2.StreamingResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A {@link HttpClient} that sends every request as one HTTP/2 stream on a
 * {@link PipeHttp2Client}, so the SDK's unmodified
 * {@code HttpClientStreamableHttpTransport} can talk to a child process.
 *
 * <p>
 * The request URI supplies {@code :scheme}, {@code :authority} and {@code :path}; it is a
 * logical URL that is never resolved. The returned future completes as the JDK client's
 * does: when the body handler's {@code getBody()} completes, which for
 * {@code BodyHandlers.ofPublisher()} is as soon as response headers arrive. Cancelling
 * the future or the response body subscription resets only this stream; the connection
 * stays usable.
 */
public final class PipeHttpClient extends SendAsyncHttpClient {

	/**
	 * Connection-specific fields that HTTP/2 forbids; {@code Host} becomes :authority.
	 */
	private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "keep-alive", "proxy-connection",
			"transfer-encoding", "upgrade");

	private final PipeHttp2Client client;

	public PipeHttpClient(PipeHttp2Client client) {
		this.client = Objects.requireNonNull(client, "client");
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler) {
		CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
		AtomicReference<StreamingResponse> stream = new AtomicReference<>();
		// The request body is collected before the stream opens (MCP bodies are finite
		// JSON documents); cancelling the result also cancels that collection.
		Disposable collecting = requestBody(request).subscribe(body -> {
			if (result.isDone()) {
				body.release();
				return;
			}
			stream.set(open(request, body, responseBodyHandler, result));
			if (result.isCancelled()) {
				cancel(stream.get());
			}
		}, failure -> result.completeExceptionally(unwrap(failure)));
		result.whenComplete((ignored, failure) -> {
			if (failure instanceof CancellationException) {
				collecting.dispose();
				cancel(stream.get());
			}
		});
		return result;
	}

	private <T> StreamingResponse open(HttpRequest request, ByteBuf requestBody,
			HttpResponse.BodyHandler<T> bodyHandler, CompletableFuture<HttpResponse<T>> result) {
		Sinks.Many<List<ByteBuffer>> bodySink = Sinks.many().unicast().onBackpressureBuffer();
		AtomicReference<StreamingResponse> self = new AtomicReference<>();
		Flux<List<ByteBuffer>> body = bodySink.asFlux().doOnCancel(() -> cancel(self.get()));
		AtomicBoolean headersSeen = new AtomicBoolean();

		URI uri = request.uri();
		StreamingResponse stream = this.client.requestStreaming(request.method(),
				uri.getScheme().toLowerCase(Locale.ROOT), uri.getRawAuthority(), path(uri), requestHeaders(request),
				requestBody, new StreamingResponse.Listener() {
					@Override
					public void onHeaders(Http2Headers headers, boolean endStream) {
						if (headersSeen.compareAndSet(false, true)) {
							deliver(request, headers, bodyHandler, body, result);
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
		self.set(stream);

		// The stream writes a retained duplicate of the request body, so this client
		// still owns the original and releases it once the stream ends.
		stream.completion().whenComplete((ignored, failure) -> {
			requestBody.release();
			if (failure != null) {
				Throwable cause = unwrap(failure);
				if (!headersSeen.get()) {
					result.completeExceptionally(cause);
				}
				bodySink.tryEmitError(cause);
			}
		});
		return stream;
	}

	private static <T> void deliver(HttpRequest request, Http2Headers headers, HttpResponse.BodyHandler<T> bodyHandler,
			Flux<List<ByteBuffer>> body, CompletableFuture<HttpResponse<T>> result) {
		try {
			int status = Integer.parseInt(String.valueOf(headers.status()));
			HttpHeaders responseHeaders = responseHeaders(headers);
			HttpResponse.ResponseInfo info = new Info(status, responseHeaders);
			HttpResponse.BodySubscriber<T> subscriber = bodyHandler.apply(info);
			JdkFlowAdapter.publisherToFlowPublisher(body).subscribe(subscriber);
			subscriber.getBody().whenComplete((value, failure) -> {
				if (failure != null) {
					result.completeExceptionally(failure);
				}
				else {
					result.complete(new Response<>(status, request, responseHeaders, value));
				}
			});
		}
		catch (RuntimeException failure) {
			result.completeExceptionally(failure);
		}
	}

	private static Mono<ByteBuf> requestBody(HttpRequest request) {
		Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
		if (publisher.isEmpty()) {
			return Mono.just(Unpooled.EMPTY_BUFFER);
		}
		return JdkFlowAdapter.flowPublisherToFlux(publisher.get())
			.collectList()
			.map(buffers -> Unpooled.wrappedBuffer(buffers.toArray(ByteBuffer[]::new)));
	}

	private static Http2Headers requestHeaders(HttpRequest request) {
		Http2Headers headers = new DefaultHttp2Headers();
		request.headers().map().forEach((name, values) -> {
			String lower = name.toLowerCase(Locale.ROOT);
			if (!lower.startsWith(":") && !NOT_FORWARDED.contains(lower)) {
				values.forEach(value -> headers.add(lower, value));
			}
		});
		return headers;
	}

	private static HttpHeaders responseHeaders(Http2Headers headers) {
		Map<String, List<String>> values = new LinkedHashMap<>();
		for (Map.Entry<CharSequence, CharSequence> entry : headers) {
			String name = entry.getKey().toString();
			if (!name.startsWith(":")) {
				values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(entry.getValue().toString());
			}
		}
		return HttpHeaders.of(values, (name, value) -> true);
	}

	private static String path(URI uri) {
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
	}

	private static Throwable unwrap(Throwable failure) {
		return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
	}

	private static void cancel(StreamingResponse stream) {
		if (stream != null) {
			stream.cancel();
		}
	}

	private record Info(int statusCode, HttpHeaders headers) implements HttpResponse.ResponseInfo {

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_2;
		}

	}

	private record Response<T>(int statusCode, HttpRequest request, HttpHeaders headers,
			T body) implements HttpResponse<T> {

		@Override
		public Optional<HttpResponse<T>> previousResponse() {
			return Optional.empty();
		}

		@Override
		public Optional<SSLSession> sslSession() {
			return Optional.empty();
		}

		@Override
		public URI uri() {
			return this.request.uri();
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_2;
		}

	}

}
