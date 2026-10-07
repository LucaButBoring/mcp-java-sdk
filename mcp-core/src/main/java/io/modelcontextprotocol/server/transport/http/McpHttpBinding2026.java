package io.modelcontextprotocol.server.transport.http;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.core.publisher.Mono;

/**
 * Opt-in MCP 2026-07-28 Streamable HTTP request-metadata binding for
 * {@link StatelessHttpDispatcher}.
 *
 * <p>
 * When configured, every JSON-RPC request must carry {@code MCP-Protocol-Version} equal
 * to {@code _meta.io.modelcontextprotocol/protocolVersion}, a supported version, an
 * {@code Mcp-Method} equal to {@code method}, an {@code Mcp-Name} for {@code tools/call},
 * {@code prompts/get}, and {@code resources/read}, and an {@code Mcp-Param-*} header for
 * every {@code x-mcp-header}-annotated argument present in a {@code tools/call} body.
 * Violations answer HTTP 400 with {@code HeaderMismatch} (-32020) or
 * {@code UnsupportedProtocolVersion} (-32022). A {@code -32601} result answers HTTP 404.
 *
 * <p>
 * Tool declarations for {@code Mcp-Param-*} validation come from the configured
 * {@link ToolResolver}; without one, the dispatcher pages through its own handler's
 * {@code tools/list} with the request's transport context. An unknown tool, or one whose
 * annotations are invalid, is not parameter-validated and is answered by the handler.
 */
public final class McpHttpBinding2026 {

	private final List<String> supportedVersions;

	private final ToolResolver toolResolver;

	private McpHttpBinding2026(Builder builder) {
		this.supportedVersions = List.copyOf(builder.supportedVersions);
		this.toolResolver = builder.toolResolver;
	}

	public List<String> supportedVersions() {
		return this.supportedVersions;
	}

	public Optional<ToolResolver> toolResolver() {
		return Optional.ofNullable(this.toolResolver);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private List<String> supportedVersions = List.of(ProtocolVersions.MCP_2026_07_28);

		private ToolResolver toolResolver;

		public Builder supportedVersions(List<String> supportedVersions) {
			this.supportedVersions = List.copyOf(Objects.requireNonNull(supportedVersions));
			return this;
		}

		public Builder toolResolver(ToolResolver toolResolver) {
			this.toolResolver = toolResolver;
			return this;
		}

		public McpHttpBinding2026 build() {
			return new McpHttpBinding2026(this);
		}

	}

	/** Resolves the current declaration of a tool by name. */
	@FunctionalInterface
	public interface ToolResolver {

		/**
		 * @param name tool name from the request body
		 * @param context transport context of the request being validated
		 * @return the tool, or empty when unknown
		 */
		Mono<Optional<McpSchema.Tool>> resolve(String name, McpTransportContext context);

	}

}
