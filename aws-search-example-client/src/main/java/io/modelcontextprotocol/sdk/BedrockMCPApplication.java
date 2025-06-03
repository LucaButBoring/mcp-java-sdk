package io.modelcontextprotocol.sdk;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

public class BedrockMCPApplication {

	private static final Logger logger = LoggerFactory.getLogger(BedrockMCPApplication.class);

	private final String systemPrompt;

	private final String modelId;

	private final Stack<Message> messages;

	private final BedrockRuntimeClient bedrockRuntimeClient;

	private final McpSyncClient mcpClient;

	public BedrockMCPApplication(BedrockRuntimeClient bedrockRuntimeClient, McpSyncClient mcpClient, String modelId,
			String systemPrompt) {
		this.bedrockRuntimeClient = bedrockRuntimeClient;
		this.mcpClient = mcpClient;
		this.modelId = modelId;
		this.systemPrompt = systemPrompt.replaceAll("\\v+", "\\\\n");
		this.messages = new Stack<>();
	}

	public void send(String message) {
		message = message.replaceAll("\\v+", "\\\\n");
		var tools = searchTools(message);
		addUserMessage(message);

		ConverseResponse converseResponse;
		do {
			var converseRequest = createConverseRequest(modelId, tools);
			converseResponse = bedrockRuntimeClient.converse(converseRequest);
		}
		while (handleConverseResponse(converseResponse));
	}

	private boolean handleConverseResponse(ConverseResponse converseResponse) {
		addMessage(converseResponse.output().message());
		printMessage(converseResponse.output().message());

		if (converseResponse.stopReason() == StopReason.TOOL_USE) {
			var toolRequest = getToolRequest(converseResponse.output().message());
			if (toolRequest == null) {
				logger.warn("Tool request is null");
				return false;
			}

			var toolResult = mcpClient
				.callTool(new McpSchema.CallToolRequest(toolRequest.name(), toolRequest.input().toString()));
			addToolResultMessage(toolRequest.toolUseId(), toolResult);
			return true;
		}

		return false;
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

	private List<McpSchema.Tool> searchTools(String query) {
		// Try to include the most recent message as context
		var lastMessage = messages.isEmpty() ? null : messages.peek();
		var fullQuery = lastMessage != null ? getText(lastMessage) + "\n" + query : query;
		fullQuery = fullQuery.replaceAll("\\v+", "\\\\n");

		// Do the search
		var response = mcpClient.searchTools(McpSchema.SearchToolsRequest.builder().query(fullQuery).build());
		var formattedResponse = String.join("\n",
				response.tools().stream().map(tool -> "\t" + tool.name() + ": " + tool.description()).toList());
		logger.info("Search results for [{}]:\n{}", query, formattedResponse);
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

	private List<ToolResultContentBlock> mcpContentToBedrockContent(List<McpSchema.Content> content) {
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

	private ConverseRequest createConverseRequest(String modelId, List<McpSchema.Tool> tools) {
		return ConverseRequest.builder()
			.modelId(modelId)
			.system(List.of(SystemContentBlock.fromText(systemPrompt)))
			.messages(messages)
			.toolConfig(ToolConfiguration.builder()
				.tools(tools.stream()
					.map(tool -> Tool.fromToolSpec(ToolSpecification.builder()
						.name(tool.name())
						.description(tool.description())
						.inputSchema(ToolInputSchema.builder().json(schemaToDocument(tool.inputSchema())).build())
						.build()))
					.toList())
				.build())
			.build();
	}

	private static Document schemaToDocument(McpSchema.JsonSchema schema) {
		return Document.mapBuilder()
			.putDocument("type", objectToDocument(schema.type()))
			.putDocument("properties", objectToDocument(schema.properties()))
			.putDocument("required", objectToDocument(schema.required(), Document.fromList(List.of())))
			.putDocument("additionalProperties", objectToDocument(schema.additionalProperties()))
			.putDocument("$defs", objectToDocument(schema.defs(), Document.fromMap(Map.of())))
			.putDocument("definitions", objectToDocument(schema.definitions(), Document.fromMap(Map.of())))
			.build();
	}

	private static Document objectToDocument(Object object, Document defaultValue) {
		var result = objectToDocument(object);
		if (result.isNull()) {
			return defaultValue;
		}

		return result;
	}

	@SuppressWarnings("unchecked lol")
	private static Document objectToDocument(Object object) {
		if (object instanceof Document d) {
			return d;
		}
		else if (object instanceof String s) {
			return Document.fromString(s);
		}
		else if (object instanceof Boolean b) {
			return Document.fromBoolean(b);
		}
		else if (object instanceof Integer i) {
			return Document.fromNumber(i);
		}
		else if (object instanceof Long l) {
			return Document.fromNumber(l);
		}
		else if (object instanceof List<?> list) {
			return Document.fromList(list.stream().map(BedrockMCPApplication::objectToDocument).toList());
		}
		else if (object instanceof Map<?, ?> map) {
			// Just assume this is a string map because that's all we ever use in
			// JSONSchema, lazy code
			var mapClone = new HashMap<String, Object>((Map<String, ?>) map);
			mapClone.replaceAll((k, v) -> {
				if (!(k instanceof String)) {
					throw new IllegalArgumentException("Map keys must be strings");
				}

				return objectToDocument(v);
			});
			return Document.fromMap((Map<String, Document>) (Map<String, ?>) mapClone);
		}
		else if (Objects.isNull(object)) {
			return Document.fromNull();
		}
		else {
			throw new IllegalArgumentException("Unsupported object type: " + object.getClass());
		}
	}

}
