package io.modelcontextprotocol.transport.httpstdio.http2;

/**
 * Raised when an HTTP/2 connection or stream terminates before its operation completes.
 */
public final class Http2TransportException extends RuntimeException {

	public Http2TransportException(String message, Throwable cause) {
		super(message, cause);
	}

}
