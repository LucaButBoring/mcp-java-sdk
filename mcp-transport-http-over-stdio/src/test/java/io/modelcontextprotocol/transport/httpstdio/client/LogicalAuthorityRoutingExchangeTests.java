package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.client.transport.http.McpHttpExchange;
import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.client.transport.http.McpHttpResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LogicalAuthorityRoutingExchangeTests {

	private final Recording pipe = new Recording("pipe");

	private final Recording network = new Recording("network");

	private final LogicalAuthorityRoutingExchange routing = LogicalAuthorityRoutingExchange.builder(this.network)
		.route("https://child.invalid:8443", this.pipe)
		.build();

	@Test
	void equivalentOriginSpellingsReachOnlyThePipe() {
		for (String url : List.of("https://child.invalid:8443/mcp", "HTTPS://CHILD.INVALID:8443/mcp",
				"https://child.invalid.:8443/.well-known/oauth-protected-resource/mcp",
				"https://Child.Invalid:8443/mcp?tenant=a#frag")) {
			assertThat(send(url)).as(url).isEqualTo("pipe");
		}
		assertThat(this.network.seen).isEmpty();
		assertThat(this.pipe.seen).hasSize(4);
	}

	@Test
	void defaultPortsAndIpv6LiteralsCompareByValue() {
		LogicalAuthorityRoutingExchange defaults = LogicalAuthorityRoutingExchange.builder(this.network)
			.route("https://child.invalid", this.pipe)
			.route("http://[::1]:7000", this.pipe)
			.build();
		assertThat(send(defaults, "https://child.invalid:443/mcp")).isEqualTo("pipe");
		assertThat(send(defaults, "https://child.invalid/mcp")).isEqualTo("pipe");
		assertThat(send(defaults, "http://[0:0:0:0:0:0:0:1]:7000/mcp")).isEqualTo("pipe");
		assertThat(this.network.seen).isEmpty();
	}

	@Test
	void otherOriginsGoToTheNetwork() {
		assertThat(send("https://idp.example/.well-known/oauth-authorization-server")).isEqualTo("network");
		assertThat(send("https://child.invalid.example:8443/mcp")).as("suffix is a different host")
			.isEqualTo("network");
		assertThat(this.pipe.seen).isEmpty();
	}

	@Test
	void theRoutedHostUnderAnotherOriginIsRejectedNotResolved() {
		for (String url : List.of("https://child.invalid/mcp", "http://child.invalid:8443/mcp",
				"https://child.invalid:9443/mcp")) {
			assertThatThrownBy(() -> send(url)).as(url)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("routed to a child process");
		}
		assertThat(this.network.seen).as("the child's private name never reaches DNS").isEmpty();
		assertThat(this.pipe.seen).isEmpty();
	}

	@Test
	void userinfoAndUnclassifiableAuthoritiesFailClosed() {
		assertThatThrownBy(() -> send("https://user:pw@child.invalid:8443/mcp"))
			.hasMessageContaining("routed to a child process");
		assertThatThrownBy(() -> send("https://chïld.invalid:8443/mcp")).hasMessageContaining("cannot be classified");
		assertThat(this.network.seen).isEmpty();
		assertThat(this.pipe.seen).isEmpty();
	}

	@Test
	void alternativeSpellingsOfARoutedIpAddressAreRejectedNotSentToTheNetwork() {
		LogicalAuthorityRoutingExchange byAddress = LogicalAuthorityRoutingExchange.builder(this.network)
			.route("https://127.0.0.1:8443", this.pipe)
			.build();
		assertThat(send(byAddress, "https://127.0.0.1:8443/mcp")).isEqualTo("pipe");
		for (String url : List.of("https://2130706433:8443/mcp", "https://0x7f.1:8443/mcp",
				"https://0177.0.0.1:8443/mcp", "https://127.1:8443/mcp", "https://[::ffff:127.0.0.1]:8443/mcp",
				"https://[::FFFF:7f00:1]:8443/mcp", "https://2130706433/mcp", "https://127.0.0.1:08443/mcp")) {
			assertThatThrownBy(() -> send(byAddress, url)).as(url).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(this.network.seen).isEmpty();
		assertThat(this.pipe.seen).hasSize(1);
		assertThat(send(byAddress, "https://192.0.2.1/x")).as("other addresses still use the network")
			.isEqualTo("network");
		assertThat(send(byAddress, "https://10.example/x")).as("a numeric label that is not last is a name")
			.isEqualTo("network");
	}

	@Test
	void aLeadingZeroPortIsNotTheRoutedAuthority() {
		assertThatThrownBy(() -> send("https://child.invalid:08443/mcp"))
			.hasMessageContaining("routed to a child process");
		assertThat(this.pipe.seen).isEmpty();
		assertThat(this.network.seen).isEmpty();
	}

	@Test
	void aPipeBoundHostHeaderMustMatchTheUriAuthority() {
		assertThat(sendWithHost("https://child.invalid:8443/mcp", "CHILD.invalid:8443")).isEqualTo("pipe");
		assertThatThrownBy(() -> sendWithHost("https://child.invalid:8443/mcp", "elsewhere.invalid:8443"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Host header");
		assertThat(this.pipe.seen).hasSize(1);
		assertThat(this.network.seen).isEmpty();
	}

	@Test
	void aPipeFailureIsNeverRetriedOnTheNetwork() {
		LogicalAuthorityRoutingExchange failing = LogicalAuthorityRoutingExchange.builder(this.network)
			.route("https://child.invalid:8443",
					(request, context) -> Mono.error(new IllegalStateException("child gone")))
			.build();
		assertThatThrownBy(() -> send(failing, "https://child.invalid:8443/mcp")).hasMessageContaining("child gone");
		assertThat(this.network.seen).isEmpty();
	}

	@Test
	void redirectsAreReturnedNotFollowed() {
		LogicalAuthorityRoutingExchange redirecting = LogicalAuthorityRoutingExchange.builder(this.network)
			.route("https://child.invalid:8443",
					(request, context) -> Mono.just(response(request, 302, "Location", "https://idp.example/steal")))
			.build();
		McpHttpResponse response = redirecting
			.exchange(request("https://child.invalid:8443/mcp", "Bearer child-token"), McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(this.network.seen).as("child-bound credentials are not replayed to the redirect target").isEmpty();
	}

	@Test
	void configurationIsValidated() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> LogicalAuthorityRoutingExchange.builder(this.network).route("child.invalid", this.pipe));
		assertThatIllegalArgumentException().isThrownBy(
				() -> LogicalAuthorityRoutingExchange.builder(this.network).route("ftp://child.invalid", this.pipe));
		assertThatIllegalArgumentException().isThrownBy(() -> LogicalAuthorityRoutingExchange.builder(this.network)
			.route("https://u@child.invalid", this.pipe));
		assertThatIllegalArgumentException().isThrownBy(() -> LogicalAuthorityRoutingExchange.builder(this.network)
			.route("https://child.invalid", this.pipe)
			.route("https://CHILD.invalid:443", this.pipe));
		for (String unreachable : List.of("https://2130706433:8443", "https://[::ffff:127.0.0.1]:8443",
				"https://child.invalid:08443")) {
			assertThatIllegalArgumentException().as(unreachable)
				.isThrownBy(() -> LogicalAuthorityRoutingExchange.builder(this.network).route(unreachable, this.pipe));
		}
		assertThatThrownBy(() -> LogicalAuthorityRoutingExchange.builder(this.network).build())
			.isInstanceOf(IllegalStateException.class);
	}

	private String send(String url) {
		return send(this.routing, url);
	}

	private static String send(LogicalAuthorityRoutingExchange exchange, String url) {
		McpHttpResponse response = exchange.exchange(request(url, null), McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		return response.headers().firstValue("x-route").orElseThrow();
	}

	private String sendWithHost(String url, String host) {
		McpHttpRequest request = McpHttpRequest.builder()
			.method("GET")
			.uri(URI.create(url))
			.headers(McpHttpHeaders.builder().add("Host", host).build())
			.build();
		return this.routing.exchange(request, McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5))
			.headers()
			.firstValue("x-route")
			.orElseThrow();
	}

	private static McpHttpRequest request(String url, String authorization) {
		McpHttpHeaders.Builder headers = McpHttpHeaders.builder();
		if (authorization != null) {
			headers.add("Authorization", authorization);
		}
		return McpHttpRequest.builder().method("GET").uri(URI.create(url)).headers(headers.build()).build();
	}

	private static McpHttpResponse response(McpHttpRequest request, int status, String header, String value) {
		return new McpHttpResponse(status, McpHttpHeaders.builder().add(header, value).build(), JdkFlowAdapter
			.publisherToFlowPublisher(Flux.just(List.of(ByteBuffer.wrap("".getBytes(StandardCharsets.UTF_8))))),
				request, "HTTP_2");
	}

	private static final class Recording implements McpHttpExchange {

		private final String name;

		private final List<URI> seen = new CopyOnWriteArrayList<>();

		private Recording(String name) {
			this.name = name;
		}

		@Override
		public Mono<McpHttpResponse> exchange(McpHttpRequest request, McpTransportContext context) {
			this.seen.add(request.uri());
			return Mono.just(response(request, 200, "x-route", this.name));
		}

	}

}
