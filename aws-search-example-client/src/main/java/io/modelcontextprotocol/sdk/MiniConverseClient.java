package io.modelcontextprotocol.sdk;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MiniConverseClient {

	private static final Logger logger = LoggerFactory.getLogger(MiniConverseClient.class);

	private int currentModelIdx;

	private final List<String> modelFallbackOrder;

	private final BedrockRuntimeClient bedrockRuntimeClient;

	public MiniConverseClient(BedrockRuntimeClient bedrockRuntimeClient, List<String> modelFallbackOrder) {
		this.bedrockRuntimeClient = bedrockRuntimeClient;
		this.modelFallbackOrder = modelFallbackOrder;
	}

	public ConverseResponse doConverse(ConverseRequest request) throws InterruptedException {
		logger.info("Making call to bedrock:Converse");

		// Fallback progressively to handle throttling
		for (var n = 0; n < modelFallbackOrder.size(); n++) {
			// Reuse a model until we get throttled heavily again
			currentModelIdx = (currentModelIdx + n) % modelFallbackOrder.size();

			var modelId = modelFallbackOrder.get(currentModelIdx);
			if (!modelId.equals(request.modelId())) {
				logger.warn("Throttled by Converse; falling back to model {}", modelId);
			}

			for (int i = 0; i < 5 /* max retries */; i++) {
				try {
					return bedrockRuntimeClient.converse(request.copy(r -> r.modelId(modelId)));
				}
				catch (BedrockRuntimeException e) {
					if (e.isThrottlingException()) {
						var delay = 100 + 100 * i * i;
						Thread.sleep(delay);
						continue;
					}

					throw e;
				}
			}
		}

		throw new RuntimeException("All retries failed");
	}

	public ConverseRequest createConverseRequest(List<Message> messages, List<McpSchema.Tool> tools,
			String systemPrompt) {
		return ConverseRequest.builder()
			.modelId(modelFallbackOrder.get(currentModelIdx))
			.system(List.of(SystemContentBlock.fromText(systemPrompt)))
			.messages(messages)
			.toolConfig(tools.isEmpty() ? null : ToolConfiguration.builder()
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
			return Document.fromList(list.stream().map(MiniConverseClient::objectToDocument).toList());
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
