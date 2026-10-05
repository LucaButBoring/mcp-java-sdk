package io.modelcontextprotocol.transport.httpstdio.http2;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;

/** Callback-oriented handle for one HTTP/2 response stream. */
public final class StreamingResponse {

	private final Listener listener;

	private final CompletableFuture<Void> opened = new CompletableFuture<>();

	private final CompletableFuture<Void> completion = new CompletableFuture<>();

	private final AtomicReference<Supplier<CompletableFuture<Void>>> cancelAction = new AtomicReference<>();

	private final AtomicReference<CompletableFuture<Void>> cancellation = new AtomicReference<>();

	StreamingResponse(Listener listener) {
		this.listener = Objects.requireNonNull(listener, "listener");
	}

	/**
	 * Cancels this stream. The reset is sent once the stream has opened, even when this
	 * method is called before opening completes. Repeated calls are idempotent and return
	 * the same future.
	 * @return a future completed after the RST_STREAM frame has been written and flushed
	 * on this side of the connection; peer observation of the reset remains asynchronous
	 */
	public CompletableFuture<Void> cancel() {
		CompletableFuture<Void> existing = this.cancellation.get();
		if (existing != null) {
			return existing;
		}
		CompletableFuture<Void> created = new CompletableFuture<>();
		if (!this.cancellation.compareAndSet(null, created)) {
			return this.cancellation.get();
		}
		// A stream that already reached its terminal state has nothing to reset; treat
		// cancellation as a completed no-op instead of writing RST_STREAM to a closed
		// stream channel. An END_STREAM racing a concurrent cancel() is still possible
		// and
		// is handled by the peer ignoring a reset for an already-closed stream.
		if (this.completion.isDone()) {
			created.complete(null);
			return created;
		}
		// Only the CAS winner registers the reset, so concurrent first callers cannot
		// issue two RST_STREAM frames.
		this.opened.thenCompose(ignored -> this.cancelAction.get().get()).whenComplete((ignored, failure) -> {
			if (failure == null) {
				created.complete(null);
			}
			else {
				created.completeExceptionally(failure);
			}
		});
		return created;
	}

	/**
	 * Returns a future that completes when end-of-stream is observed, or fails when the
	 * stream terminates abnormally.
	 * @return the terminal stream future
	 */
	public CompletableFuture<Void> completion() {
		return this.completion;
	}

	void opened(Supplier<CompletableFuture<Void>> action) {
		this.cancelAction.set(Objects.requireNonNull(action, "action"));
		this.opened.complete(null);
	}

	void failed(Throwable failure) {
		this.opened.completeExceptionally(failure);
		this.completion.completeExceptionally(failure);
	}

	void headers(Http2Headers headers, boolean endStream) {
		this.listener.onHeaders(headers, endStream);
		if (endStream) {
			complete();
		}
	}

	void data(ByteBuf data, boolean endStream) {
		this.listener.onData(data, endStream);
		if (endStream) {
			complete();
		}
	}

	void reset(long errorCode) {
		this.listener.onReset(errorCode);
		RuntimeException failure = errorCode == io.netty.handler.codec.http2.Http2Error.ENHANCE_YOUR_CALM.code()
				? new Http2BodyTooLargeException("Peer rejected an HTTP/2 body that exceeded its limit", -1)
				: new Http2StreamResetException(errorCode);
		this.completion.completeExceptionally(failure);
	}

	private void complete() {
		if (this.completion.complete(null)) {
			this.listener.onComplete();
		}
	}

	/** Receives every frame and terminal event for one response stream. */
	public interface Listener {

		default void onHeaders(Http2Headers headers, boolean endStream) {
		}

		/**
		 * Receives response data. The listener owns and must release {@code data}.
		 * @param data retained response bytes
		 * @param endStream whether this frame ends the stream
		 */
		default void onData(ByteBuf data, boolean endStream) {
			data.release();
		}

		default void onReset(long errorCode) {
		}

		default void onComplete() {
		}

	}

}
