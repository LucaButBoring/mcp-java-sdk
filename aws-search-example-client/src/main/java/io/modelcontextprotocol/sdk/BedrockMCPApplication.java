package io.modelcontextprotocol.sdk;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

public class BedrockMCPApplication {

	private static final Logger logger = LoggerFactory.getLogger(BedrockMCPApplication.class);

	private final String systemPrompt;

	private final Stack<Message> messages;

	private final McpSyncClient mcpClient;

	private final MiniConverseClient converseClient;

	public BedrockMCPApplication(BedrockRuntimeClient bedrockRuntimeClient, McpSyncClient mcpClient,
			List<String> modelIds, String systemPrompt) {
		this.converseClient = new MiniConverseClient(bedrockRuntimeClient, modelIds);
		this.mcpClient = mcpClient;
		this.systemPrompt = systemPrompt.replaceAll("\\v+", "\\\\n");
		this.messages = new Stack<>();
	}

	/**
	 * Sends a user message to the agent.
	 * @param message The user message.
	 */
	public void send(String message) throws InterruptedException {
		addUserMessage(message);

		ConverseResponse converseResponse;
		do {
			// Try to include the most recent message as search context
			var lastMessage = getLastAssistantMessage();
			var query = buildSearchQuery(message, lastMessage);
			var tools = searchTools(query);

			// Dispatch the request
			var converseRequest = converseClient.createConverseRequest(messages, tools, systemPrompt);
			converseResponse = converseClient.doConverse(converseRequest);
		} // Handle the response, which may involve another iteration
		while (handleConverseResponse(converseResponse));
	}

	private Message getLastAssistantMessage() {
		for (var message : getMessagesReversed()) {
			if (message.role() == ConversationRole.ASSISTANT) {
				return message;
			}
		}

		return null;
	}

	/**
	 * Handles the response from calling Converse.
	 * @param converseResponse The response object.
	 * @return true if a follow-up call should be made; otherwise false
	 */
	private boolean handleConverseResponse(ConverseResponse converseResponse) throws InterruptedException {
		addMessage(converseResponse.output().message());
		printMessage(converseResponse.output().message()); // Display in the console

		if (converseResponse.stopReason() == StopReason.TOOL_USE) {
			// Extract the tool call request
			var toolRequest = getToolRequest(converseResponse.output().message());
			if (toolRequest == null) {
				logger.warn("Tool request is null");
				return false;
			}

			Thread.sleep(1000); // Throttle here since we're going to send another request
			try {
				// Call the tool
				var toolResult = mcpClient
					.callTool(new McpSchema.CallToolRequest(toolRequest.name(), toolRequest.input().toString()));
				addToolResultMessage(toolRequest.toolUseId(), toolResult);
			}
			catch (McpError e) {
				logger.error("Failed to call tool {}", toolRequest.name(), e);
				addToolResultError(toolRequest.toolUseId(), e);
			}

			return true; // Send follow-up request
		}

		return false; // Do not send follow-up request
	}

	private void printMessage(Message message) {
		for (var content : message.content()) {
			if (content.type() == ContentBlock.Type.TEXT) {
				printMessage(content.text());
			}
			else if (content.type() == ContentBlock.Type.TOOL_USE) {
				var toolRequest = getToolRequest(message);
				if (toolRequest == null) {
					printMessage("Tried to call null tool?");
					continue;
				}

				printMessage("Calling tool [%s] with arguments %s".formatted(toolRequest.name(),
						toolRequest.input().toString()));
			}
			else {
				printMessage("Cannot display unhandled content type: %s".formatted(content.type()));
			}
		}
	}

	private void printMessage(String message) {
		System.out.println("\uD83E\uDD16: " + message);
	}

	private ToolUseBlock getToolRequest(Message message) {
		for (var content : message.content()) {
			if (content.type() == ContentBlock.Type.TOOL_USE) {
				return content.toolUse();
			}
		}

		return null;
	}

	private String buildSearchQuery(String query, Message lastMessage) {
		var fullQuery = lastMessage != null ? query + "\n" + getText(lastMessage) : query;
		return fullQuery.replaceAll("\\v+", "\\\\n");
	}

	private List<McpSchema.Tool> searchTools(String query) {
		// Search the server for tools matching the provided query
		var response = mcpClient.searchTools(McpSchema.SearchToolsRequest.builder().query(query).build());
		logger.info("Search results for [{}]:\n{}", query,
				String.join("\n", response.tools().stream().map(tool -> "\t" + tool.name()).toList()));
		return response.tools();
	}

	private void addUserMessage(String message) {
		addMessage(Message.builder().role("user").content(List.of(ContentBlock.fromText(message))).build());
	}

	private void addToolResultMessage(String toolUseId, McpSchema.CallToolResult toolResult) {
		addMessage(Message.builder()
			.role("user")
			.content(List.of(ContentBlock.fromToolResult(ToolResultBlock.builder()
				.toolUseId(toolUseId)
				.status(Boolean.TRUE.equals(toolResult.isError()) ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
				.content(mcpContentToBedrockContent(toolResult.content()))
				.build())))
			.build());
	}

	private void addToolResultError(String toolUseId, McpError error) {
		addMessage(Message.builder()
			.role("user")
			.content(List.of(ContentBlock.fromToolResult(ToolResultBlock.builder()
				.toolUseId(toolUseId)
				.status(ToolResultStatus.ERROR)
				.content(List.of(ToolResultContentBlock.fromText(error.getMessage())))
				.build())))
			.build());
	}

	private List<ToolResultContentBlock> mcpContentToBedrockContent(List<McpSchema.Content> content) {
		if (content == null || content.isEmpty()) {
			return Collections.emptyList();
		}

		return content.stream().map(c -> {
			if (c instanceof McpSchema.TextContent t) {
				return ToolResultContentBlock.fromText(t.text());
			}
			else {
				throw new IllegalArgumentException("Unsupported content type: " + c.getClass());
			}
		}).toList();
	}

	private void addMessage(Message message) {
		this.messages.push(message);
	}

	private static String getText(Message message) {
		var sb = new StringBuilder();
		for (var content : message.content()) {
			if (content.type() == ContentBlock.Type.TEXT) {
				sb.append(content.text());
			}
		}

		return sb.toString();
	}

	private Stack<Message> getMessagesReversed() {
		Stack<Message> reversedMessages = new Stack<>();
		for (var message : messages) {
			reversedMessages.push(message);
		}
		return reversedMessages;
	}

}
