/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.example;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.experimental.tasks.InMemoryTaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.InMemoryTaskStore;
import io.modelcontextprotocol.experimental.tasks.TaskAwareAsyncToolSpecification;
import io.modelcontextprotocol.experimental.tasks.TaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Standalone MCP server example demonstrating task-aware tools with elicitation
 * side-channeling.
 *
 * <p>
 * This server provides a tool called "elicitation-tool" that:
 * <ol>
 * <li>Creates a task when called
 * <li>Uses side-channeling to request user input via elicitation
 * <li>Returns the result once elicitation is complete
 * </ol>
 *
 * <p>
 * Run this server, then use the ExampleClient to test the flow.
 *
 * <p>
 * Usage: java -jar mcp-example-server.jar [port]
 */
public class ExampleServer {

	private static final Logger logger = LoggerFactory.getLogger(ExampleServer.class);

	private static final String MCP_ENDPOINT = "/mcp";

	private static final int DEFAULT_PORT = 8080;

	public static void main(String[] args) throws Exception {
		int port = DEFAULT_PORT;
		if (args.length > 0) {
			try {
				port = Integer.parseInt(args[0]);
			}
			catch (NumberFormatException e) {
				logger.error("Invalid port number: {}. Using default port {}.", args[0], DEFAULT_PORT);
			}
		}

		logger.info("========================================");
		logger.info("[SERVER] Starting MCP Example Server...");
		logger.info("========================================");

		// Create task infrastructure
		logger.info("[SERVER] Creating TaskStore and TaskMessageQueue...");
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		TaskMessageQueue messageQueue = new InMemoryTaskMessageQueue();

		// Create the elicitation tool
		logger.info("[SERVER] Creating elicitation-tool with side-channeling support...");
		TaskAwareAsyncToolSpecification elicitationTool = createElicitationTool();

		// Create transport provider
		logger.info("[SERVER] Creating Streamable HTTP transport provider...");
		McpTransportContextExtractor<jakarta.servlet.http.HttpServletRequest> contextExtractor = request -> McpTransportContext
			.create(Map.of("remoteAddr", request.getRemoteAddr()));

		HttpServletStreamableServerTransportProvider transportProvider = HttpServletStreamableServerTransportProvider
			.builder()
			.contextExtractor(contextExtractor)
			.mcpEndpoint(MCP_ENDPOINT)
			.keepAliveInterval(Duration.ofSeconds(5))
			.build();

		// Create and start Tomcat
		logger.info("[SERVER] Starting embedded Tomcat on port {}...", port);
		Tomcat tomcat = createTomcat(port, transportProvider);
		tomcat.start();

		// Build the MCP server
		logger.info("[SERVER] Building MCP server with task capabilities...");
		ServerCapabilities capabilities = ServerCapabilities.builder()
			.tools(true)
			.tasks(ServerCapabilities.ServerTaskCapabilities.builder().list().cancel().toolsCall().build())
			.build();

		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo("mcp-example-server", "1.0.0")
			.capabilities(capabilities)
			.taskStore(taskStore)
			.taskMessageQueue(messageQueue)
			.taskTools(elicitationTool)
			.build();

		logger.info("========================================");
		logger.info("[SERVER] MCP Server is ready!");
		logger.info("[SERVER] URL: http://localhost:{}{}", port, MCP_ENDPOINT);
		logger.info("[SERVER] Press Ctrl+C to stop.");
		logger.info("========================================");

		// Wait for shutdown signal
		CountDownLatch shutdownLatch = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			logger.info("[SERVER] Shutting down...");
			try {
				server.closeGracefully().block(Duration.ofSeconds(5));
				transportProvider.closeGracefully().block(Duration.ofSeconds(5));
				tomcat.stop();
				tomcat.destroy();
			}
			catch (Exception e) {
				logger.error("[SERVER] Error during shutdown", e);
			}
			shutdownLatch.countDown();
		}));

		// Keep the server running
		shutdownLatch.await();
	}

	/**
	 * Creates a task-aware tool that uses elicitation via side-channeling.
	 */
	private static TaskAwareAsyncToolSpecification createElicitationTool() {
		McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema("object",
				Map.of("prompt", Map.of("type", "string", "description", "The prompt to show the user")), List.of(),
				null, null, null);

		return TaskAwareAsyncToolSpecification.builder()
			.name("elicitation-tool")
			.description("A tool that requests user input via elicitation side-channeling")
			.inputSchema(inputSchema)
			.taskSupportMode(TaskSupportMode.REQUIRED)
			.createTaskHandler((args, extra) -> {
				String prompt = args.containsKey("prompt") ? args.get("prompt").toString() : "Please provide a value:";

				logger.info("[SERVER TOOL] createTaskHandler invoked with prompt: {}", prompt);

				// Create a task with short poll interval for quick testing
				return extra.createTask(opts -> opts.pollInterval(100L)).flatMap(task -> {
					String taskId = task.taskId();
					logger.info("[SERVER TOOL] Task created: {}", taskId);

					// Start background work asynchronously via side-channeling
					logger.info("[SERVER TOOL] Starting async elicitation for task {}...", taskId);

					extra.exchange()
						.createElicitation(new ElicitRequest(prompt, null, null, null), taskId)
						.doOnSubscribe(
								s -> logger.info("[SERVER TOOL] createElicitation subscribed for task {}", taskId))
						.flatMap(result -> {
							logger.info("[SERVER TOOL] Elicitation response received for task {}: {}", taskId, result);

							String response = "no-response";
							if (result.content() != null && !result.content().isEmpty()) {
								response = result.content().toString();
							}

							logger.info("[SERVER TOOL] Extracted response value: {}", response);

							// Complete the task with the result
							CallToolResult toolResult = CallToolResult.builder()
								.content(List.of(new TextContent("Got user input via side-channel: " + response)))
								.isError(false)
								.build();

							logger.info("[SERVER TOOL] Completing task {} with result...", taskId);
							return extra.completeTask(taskId, toolResult);
						})
						.doOnSuccess(v -> logger.info("[SERVER TOOL] Task {} completed successfully!", taskId))
						.doOnError(
								error -> logger.error("[SERVER TOOL] Error in task {}: {}", taskId, error.getMessage()))
						.onErrorResume(error -> {
							logger.info("[SERVER TOOL] Failing task {} due to error: {}", taskId, error.getMessage());
							return extra.failTask(taskId, error.getMessage());
						})
						.subscribe();

					// Return the task immediately without waiting for elicitation
					logger.info("[SERVER TOOL] Returning task {} immediately (async work started)", taskId);
					return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
				});
			})
			.build();
	}

	/**
	 * Creates an embedded Tomcat server with the MCP servlet.
	 */
	private static Tomcat createTomcat(int port, HttpServletStreamableServerTransportProvider transportProvider) {
		Tomcat tomcat = new Tomcat();
		tomcat.setPort(port);

		String baseDir = System.getProperty("java.io.tmpdir") + File.separator + "tomcat-mcp-example";
		tomcat.setBaseDir(baseDir);

		// Create context
		Context context = tomcat.addContext("", baseDir);

		// Add the MCP servlet
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(transportProvider);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true); // Required for SSE/streaming
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		// Configure connector for async
		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(60000); // 60 seconds async timeout

		return tomcat;
	}

}
