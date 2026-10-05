package io.modelcontextprotocol.transport.httpstdio.pipe;

/** A point-in-time snapshot of pipe-channel transfer and backpressure metrics. */
public record PipeChannelMetrics(long peakInboundQueuedBytes, long peakOutboundQueuedBytes, long bytesRead,
		long bytesWritten, long readerBlockedNanos, long writerBlockedNanos) {
}
