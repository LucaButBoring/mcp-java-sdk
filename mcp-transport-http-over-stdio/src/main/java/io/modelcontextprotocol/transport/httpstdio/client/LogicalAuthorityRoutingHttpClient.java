package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link HttpClient} that routes each request by logical origin: requests whose origin
 * matches a configured child go to that child's {@link PipeHttpClient}; every other
 * request goes to the network client.
 *
 * <p>
 * The routing is a security boundary and fails closed:
 * <ul>
 * <li>A request for a routed origin is sent only to its pipe. A pipe failure is returned
 * to the caller; it is never retried on the network.</li>
 * <li>A request whose host is a routed child's host but whose origin differs (another
 * scheme or port), or that carries userinfo, is rejected. Sending it to the network would
 * resolve the child's private name through DNS.</li>
 * <li>Responses, including redirects, are returned unchanged. The network client must not
 * follow redirects itself, so a child-bound {@code Authorization} header cannot be
 * replayed to another origin.</li>
 * </ul>
 *
 * <p>
 * Origins compare as scheme, host, and port: scheme and host case-insensitively, the
 * default port for {@code http} (80) or {@code https} (443) equal to an explicit one, a
 * single trailing dot on the host ignored, and IP literals compared by address. Hosts are
 * compared in ASCII; configure an internationalized name in its punycode form. Spellings
 * that a network client would resolve to a routed child's address, but that the child
 * could not recognize in {@code :authority}, are rejected rather than routed: the short,
 * hexadecimal, and octal IPv4 forms (for example {@code 2130706433} for
 * {@code 127.0.0.1}), IPv4-mapped IPv6 literals, and ports with leading zeros.
 */
public final class LogicalAuthorityRoutingHttpClient extends SendAsyncHttpClient {

	private final Map<Origin, HttpClient> routes;

	private final HttpClient network;

	private LogicalAuthorityRoutingHttpClient(Map<Origin, HttpClient> routes, HttpClient network) {
		this.routes = Map.copyOf(routes);
		this.network = network;
	}

	/**
	 * @param network client for every origin that is not routed to a child; it must not
	 * follow redirects
	 * @return a builder
	 */
	public static Builder builder(HttpClient network) {
		return new Builder(network);
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler) {
		URI uri = request.uri();
		Optional<Origin> origin = Origin.of(uri);
		HttpClient pipe = origin.map(this.routes::get).orElse(null);
		if (pipe != null && uri.getRawUserInfo() == null && Origin.spelledCanonically(uri)) {
			return pipe.sendAsync(request, responseBodyHandler);
		}
		if (origin.isEmpty() && uri.getRawAuthority() != null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
					"Refusing to route a request whose authority cannot be classified: " + uri.getScheme()
							+ " URL with a non-ASCII or otherwise unparsed host; use the ASCII (punycode) form"));
		}
		if (claimsRoutedHost(uri)) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Refusing to send " + redacted(uri)
					+ " to the network: its host is routed to a child process, and only the routed "
					+ "origin, spelled canonically and without userinfo, is accepted"));
		}
		return this.network.sendAsync(request, responseBodyHandler);
	}

	/** Whether the URI names any routed child's host, regardless of scheme or port. */
	private boolean claimsRoutedHost(URI uri) {
		String host = Origin.canonicalHost(uri.getHost());
		return host != null && this.routes.keySet().stream().anyMatch(origin -> origin.host().equals(host));
	}

	private static String redacted(URI uri) {
		return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
	}

	/** Configures a {@link LogicalAuthorityRoutingHttpClient}. */
	public static final class Builder {

		private final HttpClient network;

		private final Map<Origin, HttpClient> routes = new LinkedHashMap<>();

		private Builder(HttpClient network) {
			this.network = Objects.requireNonNull(network, "network");
			if (network.followRedirects() != HttpClient.Redirect.NEVER) {
				throw new IllegalArgumentException("The network client must not follow redirects");
			}
		}

		/**
		 * Routes one logical origin to a child.
		 * @param logicalOrigin an absolute {@code http} or {@code https} URL; only its
		 * scheme, host, and port are used, for example {@code https://child.invalid:8443}
		 * @param pipe the child's client, usually
		 * {@link HttpOverStdioClientTransport#httpClient()}
		 * @return this builder
		 */
		public Builder route(String logicalOrigin, HttpClient pipe) {
			Objects.requireNonNull(pipe, "pipe");
			URI uri = URI.create(logicalOrigin);
			if (uri.getRawUserInfo() != null) {
				throw new IllegalArgumentException("A routed origin must not carry userinfo: " + logicalOrigin);
			}
			Origin origin = Origin.of(uri)
				.orElseThrow(() -> new IllegalArgumentException(
						"A routed origin must be an absolute http or https URL with a host: " + logicalOrigin));
			if (!Origin.spelledCanonically(uri)) {
				throw new IllegalArgumentException("A routed origin must spell IPv4 in dotted decimal, not as "
						+ "IPv4-mapped IPv6, and its port without leading zeros: " + logicalOrigin);
			}
			if (this.routes.putIfAbsent(origin, pipe) != null) {
				throw new IllegalArgumentException("Origin is already routed: " + logicalOrigin);
			}
			return this;
		}

		public LogicalAuthorityRoutingHttpClient build() {
			if (this.routes.isEmpty()) {
				throw new IllegalStateException("At least one origin must be routed to a child");
			}
			return new LogicalAuthorityRoutingHttpClient(this.routes, this.network);
		}

	}

	/** Canonical scheme, host, and port. */
	record Origin(String scheme, String host, int port) {

		static Optional<Origin> of(URI uri) {
			String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = canonicalHost(uri.getHost());
			if (host == null || !("http".equals(scheme) || "https".equals(scheme))) {
				return Optional.empty();
			}
			int port = uri.getPort() >= 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
			return Optional.of(new Origin(scheme, host, port));
		}

		/**
		 * The host in canonical form: lowercase, one trailing dot dropped, IPv4 in dotted
		 * decimal (IPv4-mapped IPv6 included), other IPv6 bracketed in Java's full form.
		 * {@code null} when there is no host or a numeric IPv4 host is malformed.
		 */
		static String canonicalHost(String host) {
			if (host == null || host.isEmpty()) {
				return null;
			}
			String lower = host.toLowerCase(Locale.ROOT);
			if (lower.startsWith("[") && lower.endsWith("]")) {
				// The character check guarantees getByName parses a literal and never
				// falls through to name resolution.
				String literal = lower.substring(1, lower.length() - 1);
				if (!literal.matches("[0-9a-f:.]+")) {
					return lower;
				}
				try {
					InetAddress address = InetAddress.getByName(literal);
					return address instanceof Inet4Address ? address.getHostAddress()
							: "[" + address.getHostAddress() + "]";
				}
				catch (UnknownHostException invalid) {
					return lower;
				}
			}
			String name = lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
			if (!looksNumeric(name)) {
				return name;
			}
			long value = parseIpv4(name);
			return value < 0 ? null : (value >>> 24) + "." + ((value >>> 16) & 0xff) + "." + ((value >>> 8) & 0xff)
					+ "." + (value & 0xff);
		}

		/**
		 * Whether the authority is spelled as the child would see it in
		 * {@code :authority}: no alternative IPv4 spelling, no IPv4-mapped IPv6 literal,
		 * no leading zero in the port.
		 */
		static boolean spelledCanonically(URI uri) {
			String authority = uri.getRawAuthority();
			String host = uri.getHost();
			if (authority == null || host == null) {
				return false;
			}
			String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
			int hostEnd = hostAndPort.startsWith("[") ? hostAndPort.indexOf(']') + 1 : hostAndPort.lastIndexOf(':');
			String port = hostEnd <= 0 || hostEnd >= hostAndPort.length() ? "" : hostAndPort.substring(hostEnd + 1);
			if (port.length() > 1 && port.startsWith("0")) {
				return false;
			}
			String lower = host.toLowerCase(Locale.ROOT);
			if (lower.startsWith("[")) {
				String canonical = canonicalHost(host);
				return canonical != null && canonical.startsWith("[");
			}
			String name = lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
			return !looksNumeric(name) || name.equals(canonicalHost(host));
		}

		/** WHATWG URL host parsing: a host whose last label is numeric is IPv4. */
		private static boolean looksNumeric(String name) {
			String last = name.substring(name.lastIndexOf('.') + 1);
			return last.matches("[0-9]+|0x[0-9a-f]*");
		}

		/** WHATWG IPv4 parser; -1 when malformed. */
		private static long parseIpv4(String name) {
			String[] parts = name.split("\\.", -1);
			if (parts.length > 4) {
				return -1;
			}
			long[] numbers = new long[parts.length];
			for (int i = 0; i < parts.length; i++) {
				numbers[i] = parseIpv4Number(parts[i]);
				if (numbers[i] < 0 || (i < parts.length - 1 && numbers[i] > 255)) {
					return -1;
				}
			}
			long last = numbers[parts.length - 1];
			if (last >= 1L << (8 * (5 - parts.length))) {
				return -1;
			}
			long value = last;
			for (int i = 0; i < parts.length - 1; i++) {
				value += numbers[i] << (8 * (3 - i));
			}
			return value;
		}

		private static long parseIpv4Number(String part) {
			if (part.isEmpty() || part.length() > 12) {
				return -1;
			}
			int radix = 10;
			String digits = part;
			if (part.startsWith("0x")) {
				radix = 16;
				digits = part.substring(2);
				if (digits.isEmpty()) {
					return 0;
				}
			}
			else if (part.length() > 1 && part.startsWith("0")) {
				radix = 8;
				digits = part.substring(1);
			}
			try {
				return Long.parseLong(digits, radix);
			}
			catch (NumberFormatException malformed) {
				return -1;
			}
		}

	}

}
