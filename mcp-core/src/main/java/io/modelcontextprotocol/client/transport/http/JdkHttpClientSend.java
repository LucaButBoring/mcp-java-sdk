/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;

import reactor.core.publisher.Mono;

/**
 * Cancellation-safe bridge from {@link HttpClient#sendAsync} to a {@link Mono}.
 *
 * <p>
 * Internal SDK plumbing shared by the JDK-backed MCP client transports. It is public only
 * because its callers live in sibling packages; it is not a supported extension point and
 * may change without notice.
 *
 * @author Daniel Garnier-Moiroux
 */
public final class JdkHttpClientSend {

	private JdkHttpClientSend() {
	}

	/**
	 * Sends the request and emits the response with a publisher body.
	 *
	 * <p>
	 * Cancelling the returned {@link Mono} aborts the exchange. A response whose body is
	 * never subscribed to is discarded by cancelling the body so the underlying
	 * connection is released.
	 * @param httpClient client to send with
	 * @param request fully built request
	 * @return a Mono emitting the response, or completing empty when cancelled
	 */
	public static Mono<HttpResponse<Publisher<List<ByteBuffer>>>> sendAsync(HttpClient httpClient,
			HttpRequest request) {
		// Not Mono.fromFuture: cancelling aborts the exchange, and the HttpClient then
		// fails the future with a CompletionException wrapping a CancellationException,
		// which fromFuture reports as a dropped error. Only this method can cancel the
		// future, so that failure is ignored here. Replace with a plain fromFuture,
		// keeping the doOnDiscard, once
		// https://github.com/reactor/reactor-core/issues/4415 is resolved.
		return Mono.<HttpResponse<Publisher<List<ByteBuffer>>>>create(sink -> {
			CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>> exchange = httpClient.sendAsync(request,
					HttpResponse.BodyHandlers.ofPublisher());
			sink.onCancel(() -> exchange.cancel(true));
			exchange.whenComplete((response, error) -> {
				if (error == null) {
					// Emit the response so the body can be consumed. If the surrounding
					// Mono was cancelled and due to a race the headers were already
					// parsed, the call below simply discards the response.
					sink.success(response);
					return;
				}
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
						: error;
				if (cause instanceof CancellationException) {
					sink.success();
				}
				else {
					sink.error(cause);
				}
			});
		})
			// A body that is never subscribed to never releases its connection.
			.doOnDiscard(HttpResponse.class, response -> {
				if (response.body() instanceof Publisher<?> body) {
					body.subscribe(CancellingSubscriber.INSTANCE);
				}
			});
	}

	private static final class CancellingSubscriber implements Flow.Subscriber<Object> {

		private static final CancellingSubscriber INSTANCE = new CancellingSubscriber();

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.cancel();
		}

		@Override
		public void onNext(Object item) {
		}

		@Override
		public void onError(Throwable throwable) {
		}

		@Override
		public void onComplete() {
		}

	}

}
