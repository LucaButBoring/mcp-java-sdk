package io.modelcontextprotocol.transport.httpstdio.http2;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic, event-loop-free tests of {@link StreamingResponse} cancellation
 * ordering. These drive the package-private lifecycle hooks directly so the ordering
 * guarantees of {@link StreamingResponse#cancel()} are asserted without timing.
 */
class StreamingResponseCancellationTests {

	private static final StreamingResponse.Listener NO_OP = new StreamingResponse.Listener() {
	};

	@Test
	void cancellationFutureCompletesOnlyAfterResetWriteCompletes() {
		StreamingResponse response = new StreamingResponse(NO_OP);
		CompletableFuture<Void> resetWrite = new CompletableFuture<>();
		AtomicInteger resetAttempts = new AtomicInteger();

		CompletableFuture<Void> cancellation = response.cancel();
		assertThat(cancellation).as("cancel before open is pending").isNotDone();

		response.opened(() -> {
			resetAttempts.incrementAndGet();
			return resetWrite;
		});
		assertThat(resetAttempts).as("reset is issued once the stream opens").hasValue(1);
		assertThat(cancellation).as("future must not complete before the reset write completes").isNotDone();

		resetWrite.complete(null);
		assertThat(cancellation).isDone();
		assertThat(cancellation).isNotCompletedExceptionally();
	}

	@Test
	void repeatedCancelReturnsSameFutureAndIssuesOneReset() {
		StreamingResponse response = new StreamingResponse(NO_OP);
		AtomicInteger resetAttempts = new AtomicInteger();
		response.opened(() -> {
			resetAttempts.incrementAndGet();
			return CompletableFuture.completedFuture(null);
		});

		CompletableFuture<Void> first = response.cancel();
		CompletableFuture<Void> second = response.cancel();
		CompletableFuture<Void> third = response.cancel();

		assertThat(second).isSameAs(first);
		assertThat(third).isSameAs(first);
		assertThat(first).isDone();
		assertThat(resetAttempts).hasValue(1);
	}

	@Test
	void cancelAfterNormalCompletionIsANoOp() {
		StreamingResponse response = new StreamingResponse(NO_OP);
		AtomicInteger resetAttempts = new AtomicInteger();
		response.opened(() -> {
			resetAttempts.incrementAndGet();
			return CompletableFuture.completedFuture(null);
		});
		response.headers(new DefaultHttp2Headers().status("200"), false);
		response.data(Unpooled.EMPTY_BUFFER, true);
		assertThat(response.completion()).isDone();

		CompletableFuture<Void> cancellation = response.cancel();

		assertThat(cancellation).isDone();
		assertThat(cancellation).isNotCompletedExceptionally();
		assertThat(resetAttempts).as("no RST_STREAM is attempted on a completed stream").hasValue(0);
	}

	@Test
	void cancelBeforeOpenFailsWhenStreamFailsToOpen() {
		StreamingResponse response = new StreamingResponse(NO_OP);
		CompletableFuture<Void> cancellation = response.cancel();
		IllegalStateException cause = new IllegalStateException("stream open failed");

		response.failed(cause);

		assertThat(cancellation).isCompletedExceptionally();
		assertThatThrownBy(cancellation::join).isInstanceOf(CompletionException.class).hasCause(cause);
	}

	@Test
	void resetWriteFailurePropagatesToCancellationFuture() {
		StreamingResponse response = new StreamingResponse(NO_OP);
		CompletableFuture<Void> resetWrite = new CompletableFuture<>();
		response.opened(() -> resetWrite);
		CompletableFuture<Void> cancellation = response.cancel();
		IllegalStateException cause = new IllegalStateException("write failed");

		resetWrite.completeExceptionally(cause);

		assertThat(cancellation).isCompletedExceptionally();
		assertThatThrownBy(cancellation::join).isInstanceOf(CompletionException.class).hasCause(cause);
	}

}
