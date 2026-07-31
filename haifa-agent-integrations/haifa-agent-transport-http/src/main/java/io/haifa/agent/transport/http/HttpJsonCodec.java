package io.haifa.agent.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.contract.common.ContentPartDto;
import io.haifa.agent.contract.common.IdempotencyKey;
import io.haifa.agent.contract.common.ReferenceContentPartDto;
import io.haifa.agent.contract.common.TextContentPartDto;
import io.haifa.agent.contract.event.RunEventEnvelope;
import io.haifa.agent.contract.event.RunEventPage;
import io.haifa.agent.contract.event.RunEventPayload;
import io.haifa.agent.contract.interaction.InteractionResponseRequest;
import io.haifa.agent.contract.interaction.InteractionView;
import io.haifa.agent.contract.run.ResumeRunRequest;
import io.haifa.agent.contract.run.RunInputRequest;
import io.haifa.agent.contract.run.RunView;
import io.haifa.agent.contract.run.RuntimeCommandRequest;
import io.haifa.agent.contract.run.StartRunRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

final class HttpJsonCodec {
    private final ObjectMapper json;

    HttpJsonCodec(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json);
    }

    StartRunRequest start(byte[] body, Optional<String> headerKey) {
        JsonNode node = parse(body);
        return new StartRunRequest(
                new IdempotencyKey(idempotency(node, headerKey)),
                text(node, "agentDefinitionId"),
                optionalText(node, "requestedDefinitionVersion"),
                text(node, "productProfileId"),
                text(node, "sessionId"),
                optionalText(node, "projectRef"),
                text(node, "objective"),
                contents(node.path("inputs")));
    }

    RunInputRequest input(byte[] body, Optional<String> headerKey, OptionalLong ifMatch) {
        JsonNode node = parse(body);
        return new RunInputRequest(
                text(node, "inputId"),
                text(node, "runId"),
                version(node, "expectedRunVersion", ifMatch),
                contents(node.path("contents")),
                new IdempotencyKey(idempotency(node, headerKey)),
                instant(node, "submittedAt"));
    }

    ResumeRunRequest resume(byte[] body, Optional<String> headerKey, OptionalLong ifMatch) {
        JsonNode node = parse(body);
        return new ResumeRunRequest(
                text(node, "runId"),
                version(node, "expectedRunVersion", ifMatch),
                new IdempotencyKey(idempotency(node, headerKey)));
    }

    RuntimeCommandRequest command(byte[] body, Optional<String> headerKey, OptionalLong ifMatch) {
        JsonNode node = parse(body);
        return new RuntimeCommandRequest(
                text(node, "commandId"),
                text(node, "runId"),
                text(node, "commandType"),
                version(node, "expectedRunVersion", ifMatch),
                new IdempotencyKey(idempotency(node, headerKey)),
                instant(node, "requestedAt"));
    }

    InteractionResponseRequest response(byte[] body, Optional<String> headerKey, OptionalLong ifMatch) {
        JsonNode node = parse(body);
        long revision = version(node, "expectedRevision", ifMatch)
                .orElseThrow(() -> new IllegalArgumentException("expectedRevision or If-Match is required"));
        return new InteractionResponseRequest(
                text(node, "responseId"),
                text(node, "requestId"),
                text(node, "runId"),
                revision,
                text(node, "action"),
                contents(node.path("inputs")),
                new IdempotencyKey(idempotency(node, headerKey)),
                instant(node, "respondedAt"));
    }

    byte[] write(Object value) {
        ObjectNode root;
        if (value instanceof RunView view) root = runView(view);
        else if (value instanceof InteractionView view) root = interaction(view);
        else if (value instanceof io.haifa.agent.contract.interaction.InteractionResponseReceipt receipt) {
            root = interactionReceipt(receipt);
        } else if (value instanceof io.haifa.agent.contract.run.RunInputReceipt receipt) {
            root = inputReceipt(receipt);
        } else if (value instanceof io.haifa.agent.contract.run.RuntimeCommandReceipt receipt) {
            root = commandReceipt(receipt);
        } else if (value instanceof RunEventPage page) root = eventPage(page);
        else if (value instanceof RunEventEnvelope<?> event) root = event(event);
        else throw new IllegalArgumentException("unsupported response type");
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    String writeEvent(RunEventEnvelope<?> event) {
        return event(event).toString();
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode parsed = json.readTree(body);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("JSON body must be an object");
            return parsed;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("malformed JSON body", exception);
        }
    }

    private static String idempotency(JsonNode node, Optional<String> headerKey) {
        Optional<String> bodyKey = optionalText(node, "idempotencyKey");
        if (headerKey.isPresent() && bodyKey.isPresent() && !headerKey.equals(bodyKey)) {
            throw new TransportFailure(
                    io.haifa.agent.runtime.api.RuntimeErrorCode.IDEMPOTENCY_CONFLICT,
                    409,
                    "Header and body idempotency keys differ");
        }
        return headerKey
                .or(() -> bodyKey)
                .orElseThrow(() -> new IllegalArgumentException("idempotency key is required"));
    }

    private static OptionalLong version(JsonNode node, String field, OptionalLong header) {
        OptionalLong body = optionalLong(node, field);
        if (header.isPresent() && body.isPresent() && header.getAsLong() != body.getAsLong()) {
            throw new TransportFailure(
                    field.equals("expectedRevision")
                            ? io.haifa.agent.runtime.api.RuntimeErrorCode.INTERACTION_REVISION_CONFLICT
                            : io.haifa.agent.runtime.api.RuntimeErrorCode.RUN_VERSION_CONFLICT,
                    412,
                    "If-Match and body version differ");
        }
        return header.isPresent() ? header : body;
    }

    private static List<ContentPartDto> contents(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("content parts must be an array");
        List<ContentPartDto> result = new ArrayList<>();
        for (JsonNode item : node) {
            String type = text(item, "type");
            if (type.equals("text")) {
                result.add(new TextContentPartDto(text(item, "text"), text(item, "format")));
            } else if (type.equals("reference")) {
                result.add(new ReferenceContentPartDto(
                        text(item, "referenceType"), text(item, "reference"), optionalText(item, "mediaType")));
            } else {
                throw new IllegalArgumentException("unknown content part type");
            }
        }
        return List.copyOf(result);
    }

    private ObjectNode runView(RunView view) {
        ObjectNode node = base();
        node.put("runId", view.runId());
        node.put("sessionId", view.sessionId());
        node.put("status", view.status());
        node.put("version", view.version());
        node.put("updatedAt", TimePrecision.toMilliseconds(view.updatedAt()).toString());
        optional(node, "output", view.output());
        optional(node, "safeErrorCode", view.safeErrorCode());
        optional(node, "pendingInteractionId", view.pendingInteractionId());
        optional(node, "baselineCursor", view.baselineCursor());
        optional(node, "headCursor", view.headCursor());
        return node;
    }

    private ObjectNode interaction(InteractionView view) {
        ObjectNode node = base();
        node.put("requestId", view.requestId());
        node.put("runId", view.runId());
        node.put("sessionId", view.sessionId());
        node.put("revision", view.revision());
        node.put("kind", view.kind());
        node.put("state", view.state());
        node.put("title", view.title());
        node.put("safePrompt", view.safePrompt());
        ArrayNode actions = node.putArray("allowedActions");
        view.allowedActions().forEach(actions::add);
        ObjectNode input = node.putObject("inputContract");
        input.put("type", view.inputContract().type());
        input.put("maximumCharacters", view.inputContract().maximumCharacters());
        input.put("minimumSelections", view.inputContract().minimumSelections());
        input.put("maximumSelections", view.inputContract().maximumSelections());
        input.put("maximumParts", view.inputContract().maximumParts());
        input.put("maximumBytes", view.inputContract().maximumBytes());
        ArrayNode options = input.putArray("options");
        view.inputContract().options().forEach(option -> {
            ObjectNode item = options.addObject();
            item.put("id", option.id());
            item.put("label", option.label());
        });
        optional(input, "schemaRef", view.inputContract().schemaRef());
        ObjectNode target = node.putObject("target");
        target.put("type", view.target().type());
        target.put("reference", view.target().reference());
        optional(target, "version", view.target().version());
        optional(target, "digest", view.target().digest());
        target.put("safeSummary", view.target().safeSummary());
        ObjectNode requester = node.putObject("requester");
        requester.put("principalType", view.requester().principalType());
        requester.put("displayLabel", view.requester().displayLabel());
        node.put("createdAt", TimePrecision.toMilliseconds(view.createdAt()).toString());
        node.put("expiresAt", TimePrecision.toMilliseconds(view.expiresAt()).toString());
        ObjectNode consequences = node.putObject("consequences");
        consequences.put("accepted", view.consequences().accepted());
        consequences.put("rejected", view.consequences().rejected());
        consequences.put("expired", view.consequences().expired());
        return node;
    }

    private ObjectNode interactionReceipt(io.haifa.agent.contract.interaction.InteractionResponseReceipt receipt) {
        ObjectNode node = base();
        node.put("responseId", receipt.responseId());
        node.put("requestId", receipt.requestId());
        node.put("runId", receipt.runId());
        node.put("status", receipt.status());
        node.put("interactionState", receipt.interactionState());
        node.put("revision", receipt.revision());
        node.put("runVersion", receipt.runVersion());
        return node;
    }

    private ObjectNode inputReceipt(io.haifa.agent.contract.run.RunInputReceipt receipt) {
        ObjectNode node = base();
        node.put("inputId", receipt.inputId());
        node.put("runId", receipt.runId());
        node.put("status", receipt.status());
        node.put(
                "acceptedAt", TimePrecision.toMilliseconds(receipt.acceptedAt()).toString());
        receipt.appliedAt()
                .ifPresent(value -> node.put(
                        "appliedAt", TimePrecision.toMilliseconds(value).toString()));
        optional(node, "attemptId", receipt.attemptId());
        if (receipt.iteration().isPresent())
            node.put("iteration", receipt.iteration().getAsInt());
        optional(node, "reasonCode", receipt.reasonCode());
        return node;
    }

    private ObjectNode commandReceipt(io.haifa.agent.contract.run.RuntimeCommandReceipt receipt) {
        ObjectNode node = base();
        node.put("commandId", receipt.commandId());
        node.put("runId", receipt.runId());
        node.put("commandType", receipt.commandType());
        node.put("status", receipt.status());
        node.put("runVersion", receipt.runVersion());
        return node;
    }

    private ObjectNode eventPage(RunEventPage page) {
        ObjectNode node = base();
        ArrayNode items = node.putArray("items");
        page.items().forEach(item -> items.add(event(item)));
        node.put("nextCursor", page.nextCursor().value());
        node.put("headCursor", page.headCursor().value());
        node.put("hasMore", page.hasMore());
        return node;
    }

    private ObjectNode event(RunEventEnvelope<?> event) {
        ObjectNode node = base();
        node.put("eventId", event.eventId());
        node.put("eventType", event.eventType());
        node.put("eventSchemaVersion", event.eventSchemaVersion());
        node.put("runId", event.runId());
        node.put("sessionId", event.sessionId());
        node.put("sequence", event.sequence());
        node.put("cursor", event.cursor().value());
        node.put("occurredAt", TimePrecision.toMilliseconds(event.occurredAt()).toString());
        event.correlationId().ifPresent(value -> node.put("correlationId", value.value()));
        optional(node, "causationId", event.causationId());
        node.set("payload", payload(event.payload()));
        return node;
    }

    private ObjectNode payload(RunEventPayload payload) {
        ObjectNode node = json.createObjectNode();
        if (payload instanceof RunEventPayload.RunLifecycle value) {
            node.put("status", value.status());
            node.put("version", value.version());
            node.put("reasonCode", value.reasonCode());
        } else if (payload instanceof RunEventPayload.AssistantOutput value) {
            node.put("generationId", value.generationId());
            node.put("status", value.status());
            node.put("referenceOrSafeCode", value.referenceOrSafeCode());
        } else if (payload instanceof RunEventPayload.AssistantTextDelta value) {
            node.put("generationId", value.generationId());
            node.put("textDelta", value.textDelta());
        } else if (payload instanceof RunEventPayload.InteractionLifecycle value) {
            node.put("requestId", value.requestId());
            node.put("kind", value.kind());
            node.put("state", value.state());
            node.put("actionOrReason", value.actionOrReason());
        } else if (payload instanceof RunEventPayload.RunInputLifecycle value) {
            node.put("inputId", value.inputId());
            node.put("state", value.state());
            node.put("applicationCoordinate", value.applicationCoordinate());
        } else if (payload instanceof RunEventPayload.CommandResult value) {
            node.put("commandId", value.commandId());
            node.put("commandType", value.commandType());
            node.put("status", value.status());
        } else if (payload instanceof RunEventPayload.ToolLifecycle value) {
            node.put("toolCallId", value.toolCallId());
            node.put("displayName", value.displayName());
            node.put("status", value.status());
            node.put("reasonCode", value.reasonCode());
            node.put("targetSummary", value.targetSummary());
            node.put("resultRef", value.resultRef());
        } else if (payload instanceof RunEventPayload.ExecutionLifecycle value) {
            node.put("executionId", value.executionId());
            node.put("toolCallId", value.toolCallId());
            node.put("status", value.status());
            node.put("commandSummary", value.commandSummary());
            node.put("logicalWorkdir", value.logicalWorkdir());
            node.put("streamKind", value.streamKind());
            node.put("chunkOrRef", value.chunkOrRef());
            if (value.exitCode() != null) node.put("exitCode", value.exitCode());
            node.put("truncated", value.truncated());
            node.put("fileChangeSetRef", value.fileChangeSetRef());
        } else if (payload instanceof RunEventPayload.ResourceAvailable value) {
            node.put("reference", value.reference());
            node.put("kind", value.kind());
            node.put("title", value.title());
            node.put("status", value.status());
            node.put("action", value.action());
        } else {
            throw new IllegalArgumentException("unknown public event payload");
        }
        return node;
    }

    private ObjectNode base() {
        ObjectNode node = json.createObjectNode();
        node.put("apiVersion", "1.0");
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        return Optional.of(text(node, field));
    }

    private static OptionalLong optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return OptionalLong.empty();
        if (!value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return OptionalLong.of(value.longValue());
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", exception);
        }
    }

    private static void optional(ObjectNode node, String field, Optional<String> value) {
        value.ifPresent(item -> node.put(field, item));
    }
}
