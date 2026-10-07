package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.net.ssl.SSLSession;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/** A {@link HttpClient} that answers every request from a function, for tests. */
public final class StubHttpClient extends SendAsyncHttpClient {

	/** A canned response. */
	public record Reply(int status, Map<String, List<String>> headers, String body) {

		public static Reply of(int status, String body, String... headerPairs) {
			java.util.LinkedHashMap<String, List<String>> headers = new java.util.LinkedHashMap<>();
			for (int i = 0; i < headerPairs.length; i += 2) {
				headers.computeIfAbsent(headerPairs[i], ignored -> new java.util.ArrayList<>()).add(headerPairs[i + 1]);
			}
			return new Reply(status, headers, body);
		}

	}

	private final Function<HttpRequest, Reply> responder;

	/**
	 * Wraps a client so every request is recorded before it is sent.
	 * @param delegate the client that sends
	 * @param seen receives each request
	 * @return the recording client
	 */
	public static HttpClient recording(HttpClient delegate, java.util.function.Consumer<HttpRequest> seen) {
		return new SendAsyncHttpClient() {
			@Override
			public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
					HttpResponse.BodyHandler<T> responseBodyHandler) {
				seen.accept(request);
				return delegate.sendAsync(request, responseBodyHandler);
			}
		};
	}

	public StubHttpClient(Function<HttpRequest, Reply> responder) {
		this.responder = responder;
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler) {
		Reply reply;
		try {
			reply = this.responder.apply(request);
		}
		catch (RuntimeException failure) {
			return CompletableFuture.failedFuture(failure);
		}
		HttpHeaders headers = HttpHeaders.of(reply.headers(), (name, value) -> true);
		HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(new HttpResponse.ResponseInfo() {
			@Override
			public int statusCode() {
				return reply.status();
			}

			@Override
			public HttpHeaders headers() {
				return headers;
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_2;
			}
		});
		JdkFlowAdapter
			.publisherToFlowPublisher(
					Flux.just(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8)))))
			.subscribe(subscriber);
		return subscriber.getBody().<HttpResponse<T>>thenApply(body -> new HttpResponse<T>() {
			@Override
			public int statusCode() {
				return reply.status();
			}

			@Override
			public HttpRequest request() {
				return request;
			}

			@Override
			public Optional<HttpResponse<T>> previousResponse() {
				return Optional.empty();
			}

			@Override
			public HttpHeaders headers() {
				return headers;
			}

			@Override
			public T body() {
				return body;
			}

			@Override
			public Optional<SSLSession> sslSession() {
				return Optional.empty();
			}

			@Override
			public URI uri() {
				return request.uri();
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_2;
			}
		}).toCompletableFuture();
	}

}
