package io.modelcontextprotocol.transport.httpstdio.http2;

/** Raised when a peer terminates an HTTP/2 stream with RST_STREAM. */
public class Http2StreamResetException extends RuntimeException {

	private final long errorCode;

	public Http2StreamResetException(long errorCode) {
		super("HTTP/2 stream reset: " + errorCode);
		this.errorCode = errorCode;
	}

	public long errorCode() {
		return this.errorCode;
	}

}
