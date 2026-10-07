/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.server.httpstdio;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.transport.httpstdio.server.HttpOverStdioServerTransport;

/**
 * Conformance server reachable only over HTTP/2 on its own stdin and stdout, launched by
 * {@link StdioBridge}. It serves the 2026-07-28 Streamable HTTP shape through the
 * stateless SDK server, with the same tools, prompts, resources, and completions the
 * servlet conformance server uses wherever a stateless handler can produce them. Tools
 * that need a session exchange (sampling, elicitation, logging, progress) are omitted:
 * stateless handlers cannot produce them until the SDK-core 2026 work (Tracks B and C)
 * exists. Diagnostics go to stderr.
 */
public final class ConformanceStdioServer {

	private static final Map<String, Object> EMPTY_JSON_SCHEMA = Map.of("type", "object", "properties",
			Collections.emptyMap());

	private static final String RED_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

	private static final String MINIMAL_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";

	private ConformanceStdioServer() {
	}

	public static void main(String[] args) throws Exception {
		// Must run before anything else touches System.out: stdout carries HTTP/2.
		HttpOverStdioServerTransport transport = HttpOverStdioServerTransport.builderOnCurrentProcessStdio()
			.securityValidator(DefaultServerTransportSecurityValidator.builder()
				.allowedOrigin("http://localhost:*")
				.allowedOrigin("http://127.0.0.1:*")
				.allowedHost("localhost:*")
				.allowedHost("127.0.0.1:*")
				.build())
			.build();
		McpStatelessSyncServer server = McpServer.sync(transport)
			.serverInfo("java-sdk-http-over-stdio-conformance", "0.0.1")
			.capabilities(ServerCapabilities.builder()
				.completions()
				.resources(false, false)
				.tools(false)
				.prompts(false)
				.build())
			.tools(tools())
			.prompts(prompts())
			.resources(resources())
			.resourceTemplates(resourceTemplates())
			.completions(completions())
			.build();
		System.err.println("conformance-stdio-server ready");
		transport.server().closed().join();
		server.closeGracefully();
	}

	private static McpStatelessServerFeatures.SyncToolSpecification tool(Tool tool,
			java.util.function.Supplier<CallToolResult> result) {
		return McpStatelessServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((context, request) -> result.get())
			.build();
	}

	private static List<McpStatelessServerFeatures.SyncToolSpecification> tools() {
		Map<String, Object> headerSchema = Map.of("type", "object", "properties",
				Map.of("region", Map.of("type", "string", "x-mcp-header", "Region"), "priority",
						Map.of("type", "integer", "x-mcp-header", "Priority"), "verbose",
						Map.of("type", "boolean", "x-mcp-header", "Verbose"), "query", Map.of("type", "string")),
				"required", List.of("region"));
		return List.of(tool(
				Tool.builder("test_simple_text", EMPTY_JSON_SCHEMA)
					.description("Returns simple text content for testing")
					.build(),
				() -> CallToolResult.builder()
					.content(List.of(TextContent.builder("This is a simple text response for testing.").build()))
					.isError(false)
					.build()),
				tool(Tool.builder("test_image_content", EMPTY_JSON_SCHEMA)
					.description("Returns image content for testing")
					.build(),
						() -> CallToolResult.builder()
							.content(List.of(ImageContent.builder(RED_PIXEL_PNG, "image/png").build()))
							.isError(false)
							.build()),
				tool(Tool.builder("test_audio_content", EMPTY_JSON_SCHEMA)
					.description("Returns audio content for testing")
					.build(),
						() -> CallToolResult.builder()
							.content(List.of(AudioContent.builder(MINIMAL_WAV, "audio/wav").build()))
							.isError(false)
							.build()),
				tool(Tool.builder("test_embedded_resource", EMPTY_JSON_SCHEMA)
					.description("Returns embedded resource content for testing")
					.build(),
						() -> CallToolResult.builder()
							.content(List.of(
									EmbeddedResource
										.builder(TextResourceContents
											.builder("test://embedded-resource",
													"This is an embedded resource content.")
											.mimeType("text/plain")
											.build())
										.build()))
							.isError(false)
							.build()),
				tool(Tool.builder("test_multiple_content_types", EMPTY_JSON_SCHEMA)
					.description("Returns multiple content types for testing")
					.build(),
						() -> CallToolResult.builder()
							.content(List.of(TextContent.builder("Multiple content types test:").build(),
									ImageContent.builder(RED_PIXEL_PNG, "image/png").build(),
									EmbeddedResource.builder(TextResourceContents
										.builder("test://mixed-content-resource", "{\"test\":\"data\",\"value\":123}")
										.mimeType("application/json")
										.build()).build()))
							.isError(false)
							.build()),
				tool(Tool.builder("test_error_handling", EMPTY_JSON_SCHEMA)
					.description("Tool that returns an error for testing error handling")
					.build(),
						() -> CallToolResult.builder()
							.content(List.of(TextContent.builder("This tool intentionally returns an error for testing")
								.build()))
							.isError(true)
							.build()),
				// json_schema_2020_12_tool: SEP-1613 dialect and keyword preservation.
				tool(Tool
					.builder("json_schema_2020_12_tool", Map.ofEntries(
							Map.entry("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12), Map.entry("type", "object"),
							Map.entry("$defs",
									Map.of("address",
											Map.of("$anchor", "address", "type", "object", "properties",
													Map.of("street", Map.of("type", "string"), "city",
															Map.of("type", "string"))))),
							Map.entry("properties",
									Map.of("name", Map.of("type", "string"), "address",
											Map.of("$ref", "#/$defs/address"))),
							Map.entry("allOf",
									List.of(Map.of("required", List.of("name")), Map.of("anyOf",
											List.of(Map.of("required", List.of("address")),
													Map.of("properties", Map.of("name", Map.of("minLength", 1))))))),
							Map.entry("if", Map.of("properties", Map.of("name", Map.of("const", "test")))),
							Map.entry("then", Map.of("required", List.of("address"))),
							Map.entry("else", Map.of("required", List.of())), Map.entry("additionalProperties", false)))
					.description("Tool with JSON Schema 2020-12 features (SEP-1613)")
					.build(),
						() -> CallToolResult.builder()
							.content(List.of(TextContent.builder("ok").build()))
							.isError(false)
							.build()),
				// x-mcp-header coverage for the 2026-07-28 custom-header scenarios.
				McpStatelessServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder("test_headers", headerSchema)
						.description("Mirrors region, priority, and verbose into Mcp-Param headers")
						.build())
					.callHandler((context, request) -> CallToolResult.builder()
						.content(List.of(TextContent.builder("headers ok: " + request.arguments()).build()))
						.isError(false)
						.build())
					.build());
	}

	private static List<McpStatelessServerFeatures.SyncPromptSpecification> prompts() {
		return List.of(
				new McpStatelessServerFeatures.SyncPromptSpecification(
						Prompt.builder("test_simple_prompt")
							.description("A simple prompt for testing")
							.arguments(List.of())
							.build(),
						(context,
								request) -> GetPromptResult
									.builder(
											List.of(PromptMessage
												.builder(Role.USER,
														TextContent.builder("This is a simple prompt for testing.")
															.build())
												.build()))
									.build()),
				new McpStatelessServerFeatures.SyncPromptSpecification(Prompt.builder("test_prompt_with_arguments")
					.description("A prompt with arguments for testing")
					.arguments(List.of(
							PromptArgument.builder("arg1").description("First test argument").required(true).build(),
							PromptArgument.builder("arg2").description("Second test argument").required(true).build()))
					.build(), (context, request) -> {
						String text = String.format("Prompt with arguments: arg1='%s', arg2='%s'",
								request.arguments().get("arg1"), request.arguments().get("arg2"));
						return GetPromptResult
							.builder(List
								.of(PromptMessage.builder(Role.USER, TextContent.builder(text).build()).build()))
							.build();
					}),
				new McpStatelessServerFeatures.SyncPromptSpecification(
						Prompt.builder("test_prompt_with_embedded_resource")
							.description("A prompt with embedded resource for testing")
							.arguments(List.of(PromptArgument.builder("resourceUri")
								.description("URI of the resource to embed")
								.required(true)
								.build()))
							.build(),
						(context, request) -> {
							String resourceUri = (String) request.arguments().get("resourceUri");
							EmbeddedResource embedded = EmbeddedResource
								.builder(TextResourceContents
									.builder(resourceUri, "Embedded resource content for testing.")
									.mimeType("text/plain")
									.build())
								.build();
							return GetPromptResult.builder(List.of(PromptMessage.builder(Role.USER, embedded).build(),
									PromptMessage
										.builder(Role.USER,
												TextContent.builder("Please process the embedded resource above.")
													.build())
										.build()))
								.build();
						}),
				new McpStatelessServerFeatures.SyncPromptSpecification(
						Prompt
							.builder("test_prompt_with_image")
							.description("A prompt with image content for testing")
							.arguments(List.of())
							.build(),
						(context,
								request) -> GetPromptResult.builder(List.of(
										PromptMessage
											.builder(Role.USER,
													ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
											.build(),
										PromptMessage
											.builder(Role.USER,
													TextContent.builder("Please analyze the image above.").build())
											.build()))
									.build()));
	}

	private static List<McpStatelessServerFeatures.SyncResourceSpecification> resources() {
		return List.of(
				new McpStatelessServerFeatures.SyncResourceSpecification(
						Resource.builder("test://static-text", "Static Text Resource")
							.description("A static text resource for testing")
							.mimeType("text/plain")
							.build(),
						(context,
								request) -> ReadResourceResult.builder(List.of(TextResourceContents
									.builder("test://static-text", "This is the content of the static text resource.")
									.mimeType("text/plain")
									.build())).build()),
				new McpStatelessServerFeatures.SyncResourceSpecification(
						Resource.builder("test://static-binary", "Static Binary Resource")
							.description("A static binary resource for testing")
							.mimeType("image/png")
							.build(),
						(context, request) -> ReadResourceResult
							.builder(List.of(BlobResourceContents.builder("test://static-binary", RED_PIXEL_PNG)
								.mimeType("image/png")
								.build()))
							.build()));
	}

	private static List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates() {
		return List.of(new McpStatelessServerFeatures.SyncResourceTemplateSpecification(
				ResourceTemplate.builder("test://template/{id}/data", "Template Resource")
					.description("A resource template for testing parameter substitution")
					.mimeType("application/json")
					.build(),
				(context, request) -> {
					String uri = request.uri();
					String id = uri.replaceAll("test://template/(.+)/data", "$1");
					String json = String.format("{\"id\":\"%s\",\"templateTest\":true,\"data\":\"Data for ID: %s\"}",
							id, id);
					return ReadResourceResult
						.builder(List.of(TextResourceContents.builder(uri, json).mimeType("application/json").build()))
						.build();
				}));
	}

	private static List<McpStatelessServerFeatures.SyncCompletionSpecification> completions() {
		return List.of(new McpStatelessServerFeatures.SyncCompletionSpecification(
				new PromptReference("test_prompt_with_arguments"),
				(context, request) -> new CompleteResult(new CompleteResult.CompleteCompletion(List.of(), 0, false))));
	}

}
