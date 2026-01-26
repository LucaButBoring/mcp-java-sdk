/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.example;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.ErrorMessage;
import io.modelcontextprotocol.spec.McpSchema.ResponseMessage;
import io.modelcontextprotocol.spec.McpSchema.ResultMessage;
import io.modelcontextprotocol.spec.McpSchema.TaskCreatedMessage;
import io.modelcontextprotocol.spec.McpSchema.TaskMetadata;
import io.modelcontextprotocol.spec.McpSchema.TaskStatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone MCP client example demonstrating task-augmented tool calls with elicitation
 * handling.
 *
 * <p>
 * This client connects to the ExampleServer and:
 * <ol>
 * <li>Calls the "elicitation-tool" with task metadata
 * <li>Receives and handles elicitation requests from the server
 * <li>Logs all message types received during the task lifecycle
 * </ol>
 *
 * <p>
 * Usage: java -jar mcp-example-client.jar [serverUrl]
 *
 * <p>
 * Default server URL: http://localhost:8080
 */
public class ExampleClient {

	private static final Logger logger = LoggerFactory.getLogger(ExampleClient.class);

	private static final String DEFAULT_SERVER_URL = "http://localhost:8080";

	private static final String MCP_ENDPOINT = "/mcp";

	public static void main(String[] args) {
		String serverUrl = DEFAULT_SERVER_URL;
		if (args.length > 0) {
			serverUrl = args[0];
		}

		logger.info("========================================");
		logger.info("[CLIENT] Starting MCP Example Client...");
		logger.info("[CLIENT] Connecting to: {}{}", serverUrl, MCP_ENDPOINT);
		logger.info("========================================");

		// Create transport
		logger.info("[CLIENT] Creating Streamable HTTP transport...");
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl)
			.endpoint(MCP_ENDPOINT)
			.build();

		// Create client with elicitation capability
		logger.info("[CLIENT] Building MCP client with elicitation capability...");
		ClientCapabilities capabilities = ClientCapabilities.builder().elicitation().build();

		// Counter for elicitation requests
		AtomicInteger elicitationCount = new AtomicInteger(0);

		try (McpSyncClient client = McpClient.sync(transport)
			.clientInfo(new McpSchema.Implementation("mcp-example-client", "1.0.0"))
			.capabilities(capabilities)
			.requestTimeout(Duration.ofMinutes(5))
			.elicitation(elicitRequest -> {
				int count = elicitationCount.incrementAndGet();
				logger.info("[CLIENT ELICITATION] ========================================");
				logger.info("[CLIENT ELICITATION] Received elicitation request #{}!", count);
				logger.info("[CLIENT ELICITATION] Message: {}", elicitRequest.message());
				logger.info("[CLIENT ELICITATION] Returning response: value=42");
				logger.info("[CLIENT ELICITATION] ========================================");

				return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("value", "42"), null);
			})
			.build()) {

			// Initialize the client
			logger.info("[CLIENT] Initializing connection...");
			client.initialize();
			logger.info("[CLIENT] Connected successfully!");

			// List available tools
			logger.info("[CLIENT] Listing available tools...");
			var tools = client.listTools();
			logger.info("[CLIENT] Available tools: {}", tools.tools().stream().map(t -> t.name()).toList());

			// Call the elicitation tool with task metadata
			logger.info("[CLIENT] ========================================");
			logger.info("[CLIENT] Calling elicitation-tool with task metadata...");
			logger.info("[CLIENT] ========================================");

			CallToolRequest request = CallToolRequest.builder()
				.name("elicitation-tool")
				.arguments(Map.of("prompt", "What is your favorite number?"))
				.task(TaskMetadata.builder().ttl(Duration.ofSeconds(60)).build())
				.build();

			// Use callToolStream to handle the full task lifecycle
			logger.info("[CLIENT] Starting callToolStream...");

			var messages = client.callToolStream(request).toList();

			logger.info("[CLIENT] ========================================");
			logger.info("[CLIENT] Stream completed. Processing {} messages...", messages.size());
			logger.info("[CLIENT] ========================================");

			// Process all messages
			for (int i = 0; i < messages.size(); i++) {
				ResponseMessage<CallToolResult> message = messages.get(i);
				logger.info("[CLIENT] Message {}/{}: {}", i + 1, messages.size(), message.getClass().getSimpleName());

				if (message instanceof TaskCreatedMessage<?> tcm) {
					logger.info("[CLIENT]   -> Task created: {}", tcm.task().taskId());
					logger.info("[CLIENT]   -> Initial status: {}", tcm.task().status());
					logger.info("[CLIENT]   -> Poll interval: {}ms", tcm.task().pollInterval());
				}
				else if (message instanceof TaskStatusMessage<?> tsm) {
					logger.info("[CLIENT]   -> Task status update: {}", tsm.task().status());
					if (tsm.task().statusMessage() != null) {
						logger.info("[CLIENT]   -> Status message: {}", tsm.task().statusMessage());
					}
				}
				else if (message instanceof ResultMessage<?> rm) {
					logger.info("[CLIENT]   -> RESULT RECEIVED!");
					if (rm.result() != null) {
						CallToolResult result = (CallToolResult) rm.result();
						if (result.content() != null) {
							logger.info("[CLIENT]   -> Content: {}", result.content());
						}
					}
				}
				else if (message instanceof ErrorMessage<?> em) {
					logger.info("[CLIENT]   -> ERROR: {}", em.error().getMessage());
				}
				else {
					logger.info("[CLIENT]   -> Unknown message type: {}", message);
				}
			}

			logger.info("[CLIENT] ========================================");
			logger.info("[CLIENT] Example completed successfully!");
			logger.info("[CLIENT] Total elicitation requests handled: {}", elicitationCount.get());
			logger.info("[CLIENT] ========================================");
		}
		catch (Exception e) {
			logger.error("[CLIENT] Error during execution", e);
			System.exit(1);
		}
	}

}
