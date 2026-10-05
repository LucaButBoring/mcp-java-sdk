package io.modelcontextprotocol.transport.httpstdio.http2;

/** Raised when an aggregated HTTP/2 request or response exceeds its configured limit. */
public final class Http2BodyTooLargeException extends RuntimeException {

	private final long maximumBytes;

	public Http2BodyTooLargeException(String message, long maximumBytes) {
		super(message);
		this.maximumBytes = maximumBytes;
	}

	public long maximumBytes() {
		return this.maximumBytes;
	}

}
