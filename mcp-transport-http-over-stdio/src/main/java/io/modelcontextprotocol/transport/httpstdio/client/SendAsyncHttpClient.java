package io.modelcontextprotocol.transport.httpstdio.client;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A {@link HttpClient} implemented entirely by
 * {@link #sendAsync(HttpRequest, HttpResponse.BodyHandler)}. The SDK's
 * {@code HttpClientStreamableHttpTransport} only ever calls that method, so a subclass is
 * a complete replacement for the JDK client; the remaining configuration accessors report
 * a client that has no proxy, cookies, authenticator, or redirect following.
 */
abstract class SendAsyncHttpClient extends HttpClient {

	@Override
	public abstract <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler);

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
		// Push promises are never enabled on these connections.
		return sendAsync(request, responseBodyHandler);
	}

	@Override
	public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
			throws IOException, InterruptedException {
		try {
			return sendAsync(request, responseBodyHandler).get();
		}
		catch (ExecutionException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof IOException io) {
				throw io;
			}
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IOException(cause);
		}
	}

	/**
	 * Returns an {@link HttpClient.Builder} whose {@code build()} returns this client and
	 * whose other settings are ignored, for
	 * {@code HttpClientStreamableHttpTransport.Builder#clientBuilder}.
	 * @return a builder that yields this client
	 */
	public HttpClient.Builder asClientBuilder() {
		return new FixedBuilder(this);
	}

	@Override
	public Optional<CookieHandler> cookieHandler() {
		return Optional.empty();
	}

	@Override
	public Optional<Duration> connectTimeout() {
		return Optional.empty();
	}

	@Override
	public Redirect followRedirects() {
		return Redirect.NEVER;
	}

	@Override
	public Optional<ProxySelector> proxy() {
		return Optional.empty();
	}

	@Override
	public SSLContext sslContext() {
		try {
			return SSLContext.getDefault();
		}
		catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException(unavailable);
		}
	}

	@Override
	public SSLParameters sslParameters() {
		return new SSLParameters();
	}

	@Override
	public Optional<Authenticator> authenticator() {
		return Optional.empty();
	}

	@Override
	public Version version() {
		return Version.HTTP_2;
	}

	@Override
	public Optional<Executor> executor() {
		return Optional.empty();
	}

	private record FixedBuilder(HttpClient client) implements HttpClient.Builder {

		@Override
		public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
			return this;
		}

		@Override
		public HttpClient.Builder connectTimeout(Duration duration) {
			return this;
		}

		@Override
		public HttpClient.Builder sslContext(SSLContext sslContext) {
			return this;
		}

		@Override
		public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
			return this;
		}

		@Override
		public HttpClient.Builder executor(Executor executor) {
			return this;
		}

		@Override
		public HttpClient.Builder followRedirects(Redirect policy) {
			return this;
		}

		@Override
		public HttpClient.Builder version(Version version) {
			return this;
		}

		@Override
		public HttpClient.Builder priority(int priority) {
			return this;
		}

		@Override
		public HttpClient.Builder proxy(ProxySelector proxySelector) {
			return this;
		}

		@Override
		public HttpClient.Builder authenticator(Authenticator authenticator) {
			return this;
		}

		@Override
		public HttpClient build() {
			return this.client;
		}

	}

}
