package io.modelcontextprotocol.transport.httpstdio.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogicalAuthorityRoutingHttpClientTests {

	private final List<URI> pipeSeen = new CopyOnWriteArrayList<>();

	private final List<URI> networkSeen = new CopyOnWriteArrayList<>();

	private final StubHttpClient pipe = recording("pipe", this.pipeSeen);

	private final StubHttpClient network = recording("network", this.networkSeen);

	private final LogicalAuthorityRoutingHttpClient routing = LogicalAuthorityRoutingHttpClient.builder(this.network)
		.route("https://child.invalid:8443", this.pipe)
		.build();

	@Test
	void equivalentOriginSpellingsReachOnlyThePipe() {
		for (String url : List.of("https://child.invalid:8443/mcp", "HTTPS://CHILD.INVALID:8443/mcp",
				"https://child.invalid.:8443/.well-known/oauth-protected-resource/mcp",
				"https://Child.Invalid:8443/mcp?tenant=a")) {
			assertThat(send(url)).as(url).isEqualTo("pipe");
		}
		assertThat(this.networkSeen).isEmpty();
		assertThat(this.pipeSeen).hasSize(4);
	}

	@Test
	void defaultPortsAndIpv6LiteralsCompareByValue() {
		LogicalAuthorityRoutingHttpClient defaults = LogicalAuthorityRoutingHttpClient.builder(this.network)
			.route("https://child.invalid", this.pipe)
			.route("http://[::1]:7000", this.pipe)
			.build();
		assertThat(send(defaults, "https://child.invalid:443/mcp")).isEqualTo("pipe");
		assertThat(send(defaults, "https://child.invalid/mcp")).isEqualTo("pipe");
		assertThat(send(defaults, "http://[0:0:0:0:0:0:0:1]:7000/mcp")).isEqualTo("pipe");
		assertThat(this.networkSeen).isEmpty();
	}

	@Test
	void otherOriginsGoToTheNetwork() {
		assertThat(send("https://idp.example/.well-known/oauth-authorization-server")).isEqualTo("network");
		assertThat(send("https://child.invalid.example:8443/mcp")).as("suffix is a different host")
			.isEqualTo("network");
		assertThat(this.pipeSeen).isEmpty();
	}

	@Test
	void theRoutedHostUnderAnotherOriginIsRejectedNotResolved() {
		for (String url : List.of("https://child.invalid/mcp", "http://child.invalid:8443/mcp",
				"https://child.invalid:9443/mcp")) {
			assertThatThrownBy(() -> send(url)).as(url).hasMessageContaining("routed to a child process");
		}
		assertThat(this.networkSeen).as("the child's private name never reaches DNS").isEmpty();
		assertThat(this.pipeSeen).isEmpty();
	}

	@Test
	void userinfoFailsClosed() {
		assertThatThrownBy(() -> send("https://user:pw@child.invalid:8443/mcp"))
			.hasMessageContaining("routed to a child process");
		assertThat(this.networkSeen).isEmpty();
		assertThat(this.pipeSeen).isEmpty();
	}

	@Test
	void alternativeSpellingsOfARoutedIpAddressAreRejectedNotSentToTheNetwork() {
		LogicalAuthorityRoutingHttpClient byAddress = LogicalAuthorityRoutingHttpClient.builder(this.network)
			.route("https://127.0.0.1:8443", this.pipe)
			.build();
		assertThat(send(byAddress, "https://127.0.0.1:8443/mcp")).isEqualTo("pipe");
		for (String url : List.of("https://2130706433:8443/mcp", "https://0x7f.1:8443/mcp",
				"https://0177.0.0.1:8443/mcp", "https://127.1:8443/mcp", "https://[::ffff:127.0.0.1]:8443/mcp",
				"https://[::FFFF:7f00:1]:8443/mcp", "https://2130706433/mcp", "https://127.0.0.1:08443/mcp")) {
			assertThatThrownBy(() -> send(byAddress, url)).as(url).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(this.networkSeen).isEmpty();
		assertThat(this.pipeSeen).hasSize(1);
		assertThat(send(byAddress, "https://192.0.2.1/x")).as("other addresses still use the network")
			.isEqualTo("network");
		assertThat(send(byAddress, "https://10.example/x")).as("a numeric label that is not last is a name")
			.isEqualTo("network");
	}

	@Test
	void aLeadingZeroPortIsNotTheRoutedAuthority() {
		assertThatThrownBy(() -> send("https://child.invalid:08443/mcp"))
			.hasMessageContaining("routed to a child process");
		assertThat(this.pipeSeen).isEmpty();
		assertThat(this.networkSeen).isEmpty();
	}

	@Test
	void aPipeFailureIsNeverRetriedOnTheNetwork() {
		LogicalAuthorityRoutingHttpClient failing = LogicalAuthorityRoutingHttpClient.builder(this.network)
			.route("https://child.invalid:8443", new StubHttpClient(request -> {
				throw new IllegalStateException("child gone");
			}))
			.build();
		assertThatThrownBy(() -> send(failing, "https://child.invalid:8443/mcp")).hasMessageContaining("child gone");
		assertThat(this.networkSeen).isEmpty();
	}

	@Test
	void redirectsAreReturnedNotFollowed() {
		LogicalAuthorityRoutingHttpClient redirecting = LogicalAuthorityRoutingHttpClient.builder(this.network)
			.route("https://child.invalid:8443",
					new StubHttpClient(
							request -> StubHttpClient.Reply.of(302, "", "Location", "https://idp.example/steal")))
			.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://child.invalid:8443/mcp"))
			.header("Authorization", "Bearer child-token")
			.build();
		HttpResponse<?> response = PipeRequests.sendNow(redirecting, request);
		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(this.networkSeen).as("child-bound credentials are not replayed to the redirect target").isEmpty();
	}

	@Test
	void configurationIsValidated() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> LogicalAuthorityRoutingHttpClient.builder(this.network).route("child.invalid", this.pipe));
		assertThatIllegalArgumentException().isThrownBy(
				() -> LogicalAuthorityRoutingHttpClient.builder(this.network).route("ftp://child.invalid", this.pipe));
		assertThatIllegalArgumentException().isThrownBy(() -> LogicalAuthorityRoutingHttpClient.builder(this.network)
			.route("https://u@child.invalid", this.pipe));
		assertThatIllegalArgumentException().isThrownBy(() -> LogicalAuthorityRoutingHttpClient.builder(this.network)
			.route("https://child.invalid", this.pipe)
			.route("https://CHILD.invalid:443", this.pipe));
		for (String unreachable : List.of("https://2130706433:8443", "https://[::ffff:127.0.0.1]:8443",
				"https://child.invalid:08443")) {
			assertThatIllegalArgumentException().as(unreachable)
				.isThrownBy(
						() -> LogicalAuthorityRoutingHttpClient.builder(this.network).route(unreachable, this.pipe));
		}
		assertThatThrownBy(() -> LogicalAuthorityRoutingHttpClient.builder(this.network).build())
			.isInstanceOf(IllegalStateException.class);
		assertThatIllegalArgumentException().as("a redirect-following network client could replay credentials")
			.isThrownBy(() -> LogicalAuthorityRoutingHttpClient
				.builder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()));
	}

	private String send(String url) {
		return send(this.routing, url);
	}

	private static String send(HttpClient client, String url) {
		return PipeRequests.sendNow(client, PipeRequests.request("GET", url, Map.of(), null))
			.headers()
			.firstValue("x-route")
			.orElseThrow();
	}

	private static StubHttpClient recording(String name, List<URI> seen) {
		return new StubHttpClient(request -> {
			seen.add(request.uri());
			return StubHttpClient.Reply.of(200, "", "x-route", name);
		});
	}

}
