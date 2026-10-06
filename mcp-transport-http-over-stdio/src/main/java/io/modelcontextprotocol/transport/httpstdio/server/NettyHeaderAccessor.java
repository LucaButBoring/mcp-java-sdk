package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.netty.handler.codec.http2.Http2Headers;

/** Case-insensitive {@link HeaderAccessor} backed by HTTP/2 headers. */
final class NettyHeaderAccessor implements HeaderAccessor {

	private final Http2Headers headers;

	NettyHeaderAccessor(Http2Headers headers) {
		this.headers = headers;
	}

	@Override
	public List<String> getHeader(String name) {
		List<String> values = new ArrayList<>();
		for (CharSequence value : this.headers.getAll(name.toLowerCase(Locale.ROOT))) {
			values.add(value.toString());
		}
		if (values.isEmpty() && "host".equalsIgnoreCase(name) && this.headers.authority() != null) {
			values.add(this.headers.authority().toString());
		}
		return List.copyOf(values);
	}

	@Override
	public List<String> getHeaderNames() {
		List<String> names = new ArrayList<>();
		for (CharSequence name : this.headers.names()) {
			names.add(name.toString());
		}
		if (this.headers.authority() != null && names.stream().noneMatch(name -> "host".equalsIgnoreCase(name))) {
			names.add("host");
		}
		return List.copyOf(names);
	}

}
