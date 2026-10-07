package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Test helpers for sending raw requests through a {@link HttpClient}. */
public final class PipeRequests {

	private PipeRequests() {
	}

	public static HttpRequest request(String method, String url, Map<String, String> headers, String body) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.method(method,
					body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(builder::header);
		return builder.build();
	}

	/** Sends like the SDK transport does: cancelling the Mono cancels the exchange. */
	public static Mono<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> send(HttpClient client, HttpRequest request) {
		return Mono.create(sink -> {
			CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> future = client.sendAsync(request,
					HttpResponse.BodyHandlers.ofPublisher());
			sink.onCancel(() -> future.cancel(true));
			future.whenComplete((response, failure) -> {
				if (failure != null) {
					sink.error(failure.getCause() != null && failure instanceof java.util.concurrent.CompletionException
							? failure.getCause() : failure);
				}
				else {
					sink.success(response);
				}
			});
		});
	}

	public static HttpResponse<Flow.Publisher<List<ByteBuffer>>> sendNow(HttpClient client, HttpRequest request) {
		return send(client, request).block(Duration.ofSeconds(10));
	}

	public static Flux<String> text(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return JdkFlowAdapter.flowPublisherToFlux(response.body())
			.flatMapIterable(buffers -> buffers)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString());
	}

	public static Mono<String> body(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return text(response).collect(Collectors.joining());
	}

	public static String read(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return body(response).block(Duration.ofSeconds(10));
	}

}
