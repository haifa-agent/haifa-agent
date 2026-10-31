package io.haifa.agent.cli.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingModelControls;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelPreferences;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingResponseMode;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionExportResult;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationMethodView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelImageDetail;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.api.ClarificationPresentation;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionOption;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.display.BoundedText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Strict, explicit decoding from the CA Host wire schema into immutable product and Runtime values. */
final class RemoteWireCodec {
    private static final Map<String, Object> EMPTY_JSON = Map.of();

    private final ObjectMapper json;

    RemoteWireCodec(ObjectMapper json) {
        this.json = json;
    }

    CodingSessionSummary summary(JsonNode node) {
        requireObject(node, "session summary");
        Optional<AgentRunId> activeRunId =
                optionalTextValue(node, "activeRunId").map(AgentRunId::new);
        Optional<AgentRunStatus> activeRunStatus = optionalTextValue(node, "activeRunStatus")
                .map(value -> enumValue(AgentRunStatus.class, value, "activeRunStatus"));
        return new CodingSessionSummary(
                new AgentSessionId(requiredText(node, "sessionId")),
                new ProjectId(requiredText(node, "projectId")),
                requiredText(node, "displayName"),
                enumValue(AgentSessionStatus.class, requiredText(node, "status"), "status"),
                activeRunId,
                activeRunStatus,
                requiredInt(node, "queuedCount"),
                instant(node, "lastActivityAt"),
                requiredLong(node, "revision"));
    }

    CodingSessionView sessionView(JsonNode node) {
        requireObject(node, "session view");
        JsonNode preview = node.get("previewAvailable");
        if (preview != null && !preview.isNull() && (!preview.isBoolean() || preview.booleanValue())) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        JsonNode outputCursor = node.get("outputCursor");
        if (outputCursor != null && !outputCursor.isNull()) {
            long cursor = RemoteWireCodec.requiredLongValue(outputCursor, "outputCursor");
            if (cursor < 0) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
        }
        return new CodingSessionView(
                summary(requiredObject(node, "session")),
                optionalObject(node, "activeRun").map(this::run),
                optionalObject(node, "pendingInteraction").map(this::interaction),
                optionalObject(node, "eventCursor").map(cursor -> cursor(cursor, null)),
                requiredText(node, "configurationDigest"),
                requiredText(node, "productProfileRef"),
                modelSelection(requiredObject(node, "model")),
                optionalTextValue(node, "activeRunTaskSummary"));
    }

    CodingSessionHistoryPage historyPage(JsonNode node) {
        requireObject(node, "history page");
        List<CodingSessionHistoryItem> items = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "items")) {
            items.add(historyItem(item));
        }
        return new CodingSessionHistoryPage(
                new AgentSessionId(requiredText(node, "sessionId")),
                items,
                requiredBoolean(node, "earlierHistoryAvailable"));
    }

    private CodingSessionHistoryItem historyItem(JsonNode node) {
        requireObject(node, "history item");
        return new CodingSessionHistoryItem(
                requiredText(node, "id"),
                enumValue(CodingSessionHistoryItem.Kind.class, requiredText(node, "kind"), "kind"),
                requiredText(node, "title"),
                requiredText(node, "body"),
                requiredText(node, "status"),
                requiredLong(node, "sequence"),
                instant(node, "createdAt"));
    }

    List<CodingQueuedMessage> queuedMessages(JsonNode node) {
        requireObject(node, "queued messages");
        List<CodingQueuedMessage> items = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "items")) {
            requireObject(item, "queued message");
            items.add(new CodingQueuedMessage(
                    requiredText(item, "followUpId"),
                    new AgentSessionId(requiredText(item, "sessionId")),
                    requiredText(item, "summary"),
                    requiredLong(item, "sequence"),
                    requiredLong(item, "revision")));
        }
        return items;
    }

    CodingRestoredMessage restoredMessage(JsonNode node) {
        requireObject(node, "restored message");
        List<AssetRef> attachments = new ArrayList<>();
        for (JsonNode attachment : requiredArray(node, "attachments")) {
            requireObject(attachment, "attachment");
            attachments.add(new AssetRef(
                    requiredText(attachment, "assetId"),
                    requiredText(attachment, "mimeType"),
                    requiredText(attachment, "filename")));
        }
        return new CodingRestoredMessage(
                requiredText(node, "followUpId"),
                new AgentSessionId(requiredText(node, "sessionId")),
                requiredText(node, "message"),
                attachments,
                requiredLong(node, "revision"));
    }

    CodingModelSelection modelSelection(JsonNode node) {
        requireObject(node, "model selection");
        return new CodingModelSelection(
                modelOption(requiredObject(node, "model")),
                requiredLong(node, "revision"),
                requiredBoolean(node, "available"));
    }

    List<CodingModelOption> models(JsonNode node) {
        requireObject(node, "model list");
        List<CodingModelOption> models = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "models")) {
            models.add(modelOption(item));
        }
        return models;
    }

    private CodingModelOption modelOption(JsonNode node) {
        requireObject(node, "model");
        Set<String> capabilities = new LinkedHashSet<>();
        for (JsonNode capability : requiredArray(node, "capabilities")) {
            capabilities.add(requiredTextValue(capability, "capabilities"));
        }
        // The Host may retain the legacy boolean. Verified models additionally expose the complete profile;
        // the adapter never invents missing source/media/limit facts.
        JsonNode imageInputNode = node.get("imageInput");
        if (imageInputNode == null || !imageInputNode.isBoolean()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        boolean imageInput = imageInputNode.booleanValue();
        JsonNode profileNode = node.get("imageInputProfile");
        if (profileNode == null) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        Optional<ImageInputProfile> imageInputProfile;
        if (profileNode.isNull()) {
            if (imageInput) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            imageInputProfile = Optional.empty();
        } else {
            if (!imageInput || !profileNode.isObject()) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            imageInputProfile = Optional.of(imageInputProfile(profileNode));
        }
        return new CodingModelOption(
                requiredText(node, "id"),
                requiredText(node, "displayName"),
                requiredText(node, "providerId"),
                requiredText(node, "providerDisplayName"),
                capabilities,
                requiredInt(node, "contextWindow"),
                requiredInt(node, "maxOutputTokens"),
                modelState(requiredObject(node, "state")),
                optionalTextValue(node, "unavailableReason").orElse(""),
                modelControls(requiredObject(node, "controls")),
                modelPreferences(requiredObject(node, "recommendedPreferences")),
                imageInputProfile);
    }

    private ImageInputProfile imageInputProfile(JsonNode node) {
        Set<ModelImageSource> sources = new LinkedHashSet<>();
        for (JsonNode item : requiredArray(node, "allowedSources")) {
            sources.add(enumValue(ModelImageSource.class, requiredTextValue(item, "allowedSources"), "allowedSources"));
        }
        Set<String> mediaTypes = new LinkedHashSet<>();
        for (JsonNode item : requiredArray(node, "supportedMediaTypes")) {
            mediaTypes.add(requiredTextValue(item, "supportedMediaTypes"));
        }
        Set<ModelImageDetail> details = new LinkedHashSet<>();
        for (JsonNode item : requiredArray(node, "allowedDetails")) {
            details.add(enumValue(ModelImageDetail.class, requiredTextValue(item, "allowedDetails"), "allowedDetails"));
        }
        return new ImageInputProfile(
                sources,
                mediaTypes,
                requiredInt(node, "maxImagesPerRequest"),
                requiredLong(node, "maxBytesPerItem"),
                requiredLong(node, "maxTotalBytes"),
                requiredInt(node, "maxUrlCharacters"),
                requiredBoolean(node, "detailSupported"),
                details);
    }

    private CodingModelState modelState(JsonNode node) {
        return new CodingModelState(
                enumValue(CodingModelState.Connection.class, requiredText(node, "connection"), "connection"),
                enumValue(
                        CodingModelState.BindingAvailability.class,
                        requiredText(node, "bindingAvailability"),
                        "bindingAvailability"),
                enumValue(CodingModelState.RuntimeStatus.class, requiredText(node, "runtime"), "runtime"),
                enumValue(CodingModelState.RunScope.class, requiredText(node, "runScope"), "runScope"));
    }

    private CodingModelControls modelControls(JsonNode node) {
        JsonNode responseMode = requiredObject(node, "responseMode");
        JsonNode reasoningEffort = requiredObject(node, "reasoningEffort");
        return new CodingModelControls(
                new CodingModelControls.ResponseModeControl(
                        requiredText(responseMode, "kind"),
                        requiredBoolean(responseMode, "visible"),
                        requiredBoolean(responseMode, "readOnly"),
                        enumList(CodingResponseMode.class, responseMode, "allowedValues"),
                        enumValue(
                                CodingResponseMode.class,
                                requiredText(responseMode, "recommendedValue"),
                                "recommendedValue"),
                        requiredText(responseMode, "effectiveSummary")),
                new CodingModelControls.ReasoningEffortControl(
                        requiredText(reasoningEffort, "kind"),
                        requiredBoolean(reasoningEffort, "visible"),
                        requiredBoolean(reasoningEffort, "readOnly"),
                        enumList(ModelReasoningEffort.class, reasoningEffort, "allowedValues"),
                        enumValue(
                                ModelReasoningEffort.class,
                                requiredText(reasoningEffort, "recommendedValue"),
                                "recommendedValue"),
                        requiredText(reasoningEffort, "effectiveSummary")));
    }

    private CodingModelPreferences modelPreferences(JsonNode node) {
        return new CodingModelPreferences(
                enumValue(CodingResponseMode.class, requiredText(node, "responseMode"), "responseMode"),
                optionalTextValue(node, "effort").map(value -> enumValue(ModelReasoningEffort.class, value, "effort")));
    }

    CodingSessionExportResult exportResult(JsonNode node) {
        requireObject(node, "export result");
        return new CodingSessionExportResult(
                requiredText(node, "logicalPath"),
                requiredInt(node, "messageCount"),
                requiredText(node, "schemaVersion"));
    }

    CodingCompactionResult compaction(JsonNode node) {
        requireObject(node, "compaction result");
        return new CodingCompactionResult(
                optionalRawText(node, "summaryReference"),
                requiredLong(node, "summaryVersion"),
                requiredInt(node, "sourceMessageCount"),
                requiredInt(node, "estimatedTokens"),
                new MessageCursor(requiredLong(requiredObject(node, "through"), "value")));
    }

    CodingShellPlan shellPlan(JsonNode node) {
        requireObject(node, "shell plan");
        return new CodingShellPlan(
                requiredText(node, "token"),
                new AgentSessionId(requiredText(node, "sessionId")),
                requiredText(node, "safeCommand"),
                requiredBoolean(node, "includeInContext"),
                enumValue(CodingShellPlan.State.class, requiredText(node, "state"), "state"),
                requiredText(node, "reasonCode"));
    }

    CodingShellResult shellResult(JsonNode node) {
        requireObject(node, "shell result");
        return new CodingShellResult(
                requiredText(node, "processState"),
                optionalInteger(node, "exitCode"),
                requiredText(node, "safeSummary"),
                optionalRawText(node, "outputReference"),
                requiredBoolean(node, "truncated"),
                requiredBoolean(node, "includedInContext"));
    }

    List<String> textList(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : requiredArray(node, field)) {
            values.add(requiredTextValue(item, field));
        }
        return values;
    }

    List<CodingWorkspaceView> workspaces(JsonNode node) {
        requireObject(node, "workspace list");
        List<CodingWorkspaceView> workspaces = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "workspaces")) {
            requireObject(item, "workspace");
            workspaces.add(new CodingWorkspaceView(
                    requiredText(item, "workspaceRef"),
                    requiredText(item, "safeDisplayName"),
                    requiredText(item, "mode"),
                    requiredText(item, "status"),
                    requiredBoolean(item, "revocable")));
        }
        return workspaces;
    }

    boolean revoked(JsonNode node, String field) {
        requireObject(node, "boolean receipt");
        return requiredBoolean(node, field);
    }

    AgentRunSnapshot run(JsonNode node) {
        requireObject(node, "run snapshot");
        AgentRunId runId = new AgentRunId(requiredText(node, "runId"));
        Instant updatedAt = instant(node, "updatedAt");
        Optional<AgentError> error = optionalTextValue(node, "errorCode")
                .map(code -> new AgentError(
                        AgentErrorCode.fromWireCode(code),
                        EMPTY_JSON,
                        optionalTextValue(node, "diagnosticId").orElse(null),
                        updatedAt));
        Optional<RunTerminationReason> termination = optionalObject(node, "terminationReason")
                .map(value ->
                        new RunTerminationReason(requiredText(value, "code"), requiredText(value, "description")));
        return new AgentRunSnapshot(
                runId,
                enumValue(AgentRunStatus.class, requiredText(node, "status"), "status"),
                requiredLong(node, "version"),
                updatedAt,
                optionalObject(node, "result").map(this::result),
                error,
                optionalRawText(node, "output"),
                usage(requiredObject(node, "usage")),
                termination);
    }

    private AgentRunUsage usage(JsonNode node) {
        return new AgentRunUsage(
                requiredLong(node, "inputTokens"),
                requiredLong(node, "outputTokens"),
                requiredLong(node, "cachedInputTokens"),
                requiredLong(node, "modelCalls"),
                requiredLong(node, "toolCalls"),
                requiredLong(node, "childRuns"),
                requiredLong(node, "costMinorUnits"),
                requiredLong(node, "wallTimeMillis"));
    }

    private AgentRunResult result(JsonNode node) {
        List<ArtifactRef> artifacts = new ArrayList<>();
        for (JsonNode artifact : requiredArray(node, "artifacts")) {
            requireObject(artifact, "artifact");
            artifacts.add(new ArtifactRef(
                    requiredText(artifact, "artifactId"),
                    requiredText(artifact, "artifactType"),
                    requiredText(artifact, "version"),
                    requiredText(artifact, "title")));
        }
        return new AgentRunResult(
                enumValue(AgentRunOutcome.class, requiredText(node, "outcome"), "outcome"),
                requiredText(node, "summary"),
                nullableText(node, "outputSchemaId").orElse(""),
                nullableText(node, "outputSchemaVersion").orElse(""),
                structuredOutput(node.get("structuredOutput")),
                artifacts,
                textList(node, "warnings"));
    }

    private Map<String, Object> structuredOutput(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return json.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    InteractionView interaction(JsonNode node) {
        requireObject(node, "interaction");
        List<InteractionAction> actions = new ArrayList<>();
        for (JsonNode action : requiredArray(node, "allowedActions")) {
            actions.add(new InteractionAction(requiredTextValue(action, "allowedActions")));
        }
        JsonNode expiresAt = node.get("expiresAt");
        Optional<Instant> expires =
                expiresAt == null || expiresAt.isNull() ? Optional.empty() : Optional.of(epoch(expiresAt, "expiresAt"));
        return new InteractionView(
                new InteractionRequestId(requiredText(node, "interactionId")),
                new AgentRunId(requiredText(node, "runId")),
                new AgentSessionId(requiredText(node, "sessionId")),
                requiredLong(node, "revision"),
                new InteractionKind(requiredText(node, "kind")),
                enumValue(InteractionState.class, requiredText(node, "state"), "state"),
                requiredText(node, "title"),
                requiredText(node, "safePrompt"),
                actions,
                inputContract(requiredObject(node, "inputContract")),
                target(requiredObject(node, "target")),
                requester(requiredObject(node, "requester")),
                instant(node, "createdAt"),
                expires,
                consequences(requiredObject(node, "consequences")),
                optionalObject(node, "approvalPresentation").map(this::approvalPresentation),
                optionalObject(node, "clarificationPresentation").map(this::clarificationPresentation));
    }

    private InteractionInputContract inputContract(JsonNode node) {
        List<InteractionOption> options = new ArrayList<>();
        for (JsonNode option : requiredArray(node, "options")) {
            requireObject(option, "input option");
            options.add(new InteractionOption(requiredText(option, "id"), requiredText(option, "label")));
        }
        return new InteractionInputContract(
                new InteractionInputType(requiredText(node, "type")),
                requiredInt(node, "maximumCharacters"),
                requiredInt(node, "minimumSelections"),
                requiredInt(node, "maximumSelections"),
                requiredInt(node, "maximumParts"),
                requiredInt(node, "maximumBytes"),
                options,
                optionalTextValue(node, "schemaRef"));
    }

    private InteractionTargetView target(JsonNode node) {
        return new InteractionTargetView(
                requiredText(node, "type"),
                requiredText(node, "reference"),
                optionalTextValue(node, "version"),
                optionalTextValue(node, "digest"),
                requiredText(node, "safeSummary"));
    }

    private InteractionRequesterView requester(JsonNode node) {
        return new InteractionRequesterView(requiredText(node, "principalType"), requiredText(node, "displayLabel"));
    }

    private InteractionConsequenceView consequences(JsonNode node) {
        return new InteractionConsequenceView(
                requiredText(node, "accepted"), requiredText(node, "rejected"), requiredText(node, "expired"));
    }

    private ApprovalPresentation approvalPresentation(JsonNode node) {
        return new ApprovalPresentation(
                requiredText(node, "title"),
                requiredText(node, "purpose"),
                requiredText(node, "contentType"),
                requiredText(node, "content"),
                facts(node, "environment"),
                facts(node, "technical"),
                optionalTextValue(node, "risk"));
    }

    private List<ApprovalPresentation.Fact> facts(JsonNode node, String field) {
        List<ApprovalPresentation.Fact> facts = new ArrayList<>();
        for (JsonNode item : requiredArray(node, field)) {
            requireObject(item, field);
            facts.add(new ApprovalPresentation.Fact(requiredText(item, "label"), requiredText(item, "value")));
        }
        return facts;
    }

    private ClarificationPresentation clarificationPresentation(JsonNode node) {
        List<ClarificationPresentation.Question> questions = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "questions")) {
            requireObject(item, "clarification question");
            List<ClarificationPresentation.Option> options = new ArrayList<>();
            for (JsonNode option : requiredArray(item, "options")) {
                requireObject(option, "clarification option");
                options.add(new ClarificationPresentation.Option(
                        requiredText(option, "optionId"),
                        requiredText(option, "label"),
                        optionalTextValue(option, "description")));
            }
            questions.add(new ClarificationPresentation.Question(
                    requiredText(item, "header"),
                    requiredText(item, "question"),
                    requiredBoolean(item, "multiple"),
                    requiredBoolean(item, "allowCustom"),
                    options));
        }
        return new ClarificationPresentation(requiredText(node, "title"), questions);
    }

    RunEventCursor cursor(JsonNode node, AgentRunId expectedRunId) {
        requireObject(node, "event cursor");
        AgentRunId runId = new AgentRunId(requiredText(node, "runId"));
        if (expectedRunId != null && !expectedRunId.equals(runId)) {
            throw new ProjectProductException(
                    "EVENT_CURSOR_RUN_MISMATCH", "Remote event cursor belongs to another Run");
        }
        String feedVersion = requiredText(node, "feedVersion");
        JsonNode sequence = node.get("sequence");
        OptionalLong exclusive = sequence == null || sequence.isNull()
                ? OptionalLong.empty()
                : OptionalLong.of(positiveLong(sequence, "sequence"));
        return new RunEventCursor(runId, feedVersion, exclusive);
    }

    RunEventPage eventPage(JsonNode node) {
        requireObject(node, "event page");
        List<AgentRunEvent> items = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "items")) {
            items.add(event(item));
        }
        return new RunEventPage(
                items,
                cursor(requiredObject(node, "nextCursor"), null),
                cursor(requiredObject(node, "headCursor"), null),
                requiredBoolean(node, "hasMore"));
    }

    AgentRunEvent event(JsonNode node) {
        requireObject(node, "run event");
        AgentRunId runId = new AgentRunId(requiredText(node, "runId"));
        JsonNode payload = node.get("payload");
        JsonNode unsupported = node.get("payloadUnsupported");
        if (unsupported != null && !unsupported.isNull()) {
            if (!unsupported.isBoolean() || unsupported.booleanValue()) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
        }
        if (payload == null || payload.isNull()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return new AgentRunEvent(
                requiredText(node, "eventId"),
                requiredText(node, "eventType"),
                requiredText(node, "eventSchemaVersion"),
                runId,
                new AgentSessionId(requiredText(node, "sessionId")),
                requiredLong(node, "sequence"),
                cursor(requiredObject(node, "cursor"), runId),
                instant(node, "occurredAt"),
                optionalTextValue(node, "correlationId"),
                optionalTextValue(node, "causationId"),
                payload(payload));
    }

    private AgentRunEvent.Payload payload(JsonNode node) {
        requireObject(node, "event payload");
        String kind = requiredText(node, "kind");
        return switch (kind) {
            case "message-committed" ->
                new RunEventPayloads.MessageCommitted(
                        requiredText(node, "messageId"), requiredLong(node, "messageSequence"));
            case "run-lifecycle" ->
                new RunEventPayloads.RunLifecycle(
                        requiredText(node, "status"),
                        requiredLong(node, "version"),
                        requiredText(node, "reasonCode"),
                        optionalRawText(node, "errorMessage"),
                        optionalTextValue(node, "diagnosticId"));
            case "assistant-output" ->
                new RunEventPayloads.AssistantOutput(
                        requiredText(node, "generationId"),
                        requiredText(node, "status"),
                        requiredText(node, "referenceOrSafeCode"));
            case "assistant-text-delta" ->
                new RunEventPayloads.AssistantTextDelta(
                        requiredText(node, "generationId"), requiredText(node, "textDelta"));
            case "interaction-lifecycle" ->
                new RunEventPayloads.InteractionLifecycle(
                        requiredText(node, "requestId"),
                        requiredText(node, "interactionKind"),
                        requiredText(node, "state"),
                        requiredText(node, "actionOrReason"));
            case "run-input-lifecycle" ->
                new RunEventPayloads.RunInputLifecycle(
                        requiredText(node, "inputId"),
                        requiredText(node, "state"),
                        requiredText(node, "applicationCoordinate"));
            case "command-result" ->
                new RunEventPayloads.CommandResult(
                        requiredText(node, "commandId"),
                        requiredText(node, "commandType"),
                        requiredText(node, "status"));
            case "model-lifecycle" ->
                new RunEventPayloads.ModelLifecycle(
                        requiredText(node, "modelCallId"),
                        requiredText(node, "providerId"),
                        requiredText(node, "modelId"),
                        requiredText(node, "status"),
                        requiredInt(node, "iteration"),
                        requiredInt(node, "attempt"),
                        requiredLong(node, "inputTokens"),
                        requiredLong(node, "outputTokens"),
                        optionalRawText(node, "finishReason").orElse(""),
                        requiredText(node, "reasonCode"));
            case "model-attempt-lifecycle" ->
                new RunEventPayloads.ModelAttemptLifecycle(
                        requiredText(node, "modelRequestId"),
                        optionalTextValue(node, "modelCallId").orElse(""),
                        requiredText(node, "status"),
                        requiredInt(node, "attempt"),
                        requiredLong(node, "delayMillis"),
                        requiredText(node, "reasonCode"));
            case "tool-lifecycle" -> toolLifecycle(node);
            case "resource-available" ->
                new RunEventPayloads.ResourceAvailable(
                        requiredText(node, "reference"),
                        requiredText(node, "resourceKind"),
                        requiredText(node, "title"),
                        requiredText(node, "status"),
                        requiredText(node, "action"));
            case "delivery-lifecycle" ->
                new RunEventPayloads.DeliveryLifecycle(
                        requiredText(node, "phase"),
                        requiredText(node, "status"),
                        requiredText(node, "reasonCode"),
                        textList(node, "missingEvidence"),
                        requiredInt(node, "remainingPercent"),
                        requiredInt(node, "attempt"),
                        requiredText(node, "limitingResource"),
                        requiredLong(node, "limitingUsed"),
                        requiredLong(node, "limitingLimit"));
            case "child-run-lifecycle" ->
                new RunEventPayloads.ChildRunLifecycle(
                        requiredText(node, "childRunId"),
                        requiredText(node, "toolCallId"),
                        requiredText(node, "childAgent"),
                        requiredText(node, "status"),
                        requiredText(node, "reasonCode"));
            default -> throw RemoteRpcTransport.protocolIncompatible();
        };
    }

    private RunEventPayloads.ToolLifecycle toolLifecycle(JsonNode node) {
        Optional<RunEventPayloads.ToolObservation> observation =
                optionalObject(node, "observation").map(this::toolObservation);
        return new RunEventPayloads.ToolLifecycle(
                requiredText(node, "toolCallId"),
                requiredText(node, "displayName"),
                requiredText(node, "status"),
                requiredText(node, "reasonCode"),
                optionalRawText(node, "targetSummary").orElse(""),
                optionalRawText(node, "resultRef").orElse(""),
                observation,
                optionalRawText(node, "intentSummary").orElse(""),
                optionalRawText(node, "toolName").orElse(""));
    }

    private RunEventPayloads.ToolObservation toolObservation(JsonNode node) {
        Optional<BoundedText> preview = optionalObject(node, "outputPreview").map(this::boundedText);
        return new RunEventPayloads.ToolObservation(
                preview, optionalRawText(node, "processState"), optionalInteger(node, "exitCode"));
    }

    private BoundedText boundedText(JsonNode node) {
        return new BoundedText(
                requiredRawText(node, "text"),
                requiredBoolean(node, "truncated"),
                requiredLong(node, "byteCount"),
                requiredLong(node, "lineCount"),
                optionalTextValue(node, "truncationReason")
                        .map(value -> enumValue(BoundedText.TruncationReason.class, value, "truncationReason")));
    }

    AgentRunOutputEvent outputEvent(JsonNode node) {
        requireObject(node, "output event");
        return new AgentRunOutputEvent(
                new AgentRunId(requiredText(node, "runId")),
                requiredText(node, "modelCallId"),
                requiredText(node, "generationId"),
                requiredInt(node, "physicalAttempt"),
                requiredLong(node, "sequence"),
                enumValue(AgentRunOutputEventType.class, requiredText(node, "type"), "type"),
                requiredRawText(node, "textDelta"),
                instant(node, "occurredAt"));
    }

    String outputSessionId(JsonNode node) {
        return requiredText(node, "sessionId");
    }

    List<CodingAuthenticationProviderView> authenticationProviders(JsonNode node) {
        requireObject(node, "authentication status");
        List<CodingAuthenticationProviderView> providers = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "providers")) {
            providers.add(authenticationProvider(item));
        }
        return providers;
    }

    List<CodingAuthenticationView> authenticationConnections(JsonNode node) {
        requireObject(node, "authentication status");
        List<CodingAuthenticationView> connections = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "connections")) {
            connections.add(authenticationConnection(item));
        }
        return connections;
    }

    boolean authenticationConnectionRequired(JsonNode node) {
        requireObject(node, "authentication status");
        return requiredBoolean(node, "connectionRequired");
    }

    String authenticationApiKeyProviderId(JsonNode node) {
        requireObject(node, "authentication status");
        return requiredText(node, "apiKeyProviderId");
    }

    boolean authenticationLogoutSupported(JsonNode node) {
        requireObject(node, "authentication status");
        return requiredBoolean(node, "logout");
    }

    private CodingAuthenticationProviderView authenticationProvider(JsonNode node) {
        requireObject(node, "authentication provider");
        List<CodingAuthenticationMethodView> methods = new ArrayList<>();
        for (JsonNode method : requiredArray(node, "methods")) {
            requireObject(method, "authentication method");
            methods.add(new CodingAuthenticationMethodView(
                    requiredText(method, "id"),
                    enumValue(CodingAuthenticationMethodView.Type.class, requiredText(method, "type"), "method type"),
                    requiredBoolean(method, "available"),
                    optionalTextValue(method, "unavailableReason")));
        }
        return new CodingAuthenticationProviderView(
                requiredText(node, "providerId"),
                requiredText(node, "displayName"),
                enumValue(CodingAuthenticationProviderView.Category.class, requiredText(node, "category"), "category"),
                requiredBoolean(node, "connected"),
                requiredBoolean(node, "recommended"),
                requiredBoolean(node, "current"),
                optionalTextValue(node, "accountLabel"),
                optionalTextValue(node, "planLabel"),
                methods,
                optionalTextValue(node, "unavailableReason"));
    }

    private CodingAuthenticationView authenticationConnection(JsonNode node) {
        requireObject(node, "authentication connection");
        JsonNode resetAt = node.get("resetAtEpochMillis");
        OptionalLong reset = resetAt == null || resetAt.isNull()
                ? OptionalLong.empty()
                : OptionalLong.of(nonNegativeLong(resetAt, "resetAtEpochMillis"));
        return new CodingAuthenticationView(
                requiredText(node, "connectionId"),
                requiredText(node, "providerId"),
                enumValue(CodingAuthenticationView.Method.class, requiredText(node, "method"), "method"),
                enumValue(CodingAuthenticationView.Status.class, requiredText(node, "status"), "status"),
                requiredText(node, "accountLabel"),
                optionalTextValue(node, "planType"),
                reset,
                requiredBoolean(node, "unofficialLocalCompatibility"));
    }

    Optional<Integer> optionalInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(requiredIntValue(value, field));
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.textValue();
    }

    static String requiredRawText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.textValue();
    }

    static String requiredTextValue(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.textValue();
    }

    static Optional<String> optionalTextValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.textValue().isBlank() ? Optional.empty() : Optional.of(value.textValue());
    }

    static Optional<String> optionalRawText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return Optional.of(value.textValue());
    }

    static Optional<String> nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return Optional.of(value.textValue());
    }

    static long requiredLong(JsonNode node, String field) {
        return requiredLongValue(requireField(node, field), field);
    }

    static long requiredLongValue(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.longValue();
    }

    static int requiredInt(JsonNode node, String field) {
        return requiredIntValue(requireField(node, field), field);
    }

    static int requiredIntValue(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.intValue();
    }

    static long positiveLong(JsonNode node, String field) {
        long value = requiredLongValue(node, field);
        if (value < 1) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value;
    }

    static long nonNegativeLong(JsonNode node, String field) {
        long value = requiredLongValue(node, field);
        if (value < 0) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value;
    }

    static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = requireField(node, field);
        if (!value.isBoolean()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value.booleanValue();
    }

    static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = requireField(node, field);
        if (!value.isObject()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value;
    }

    static Optional<JsonNode> optionalObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isObject()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return Optional.of(value);
    }

    static Iterable<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = requireField(node, field);
        if (!value.isArray()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value;
    }

    static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }

    static Instant instant(JsonNode node, String field) {
        return epoch(requireField(node, field), field);
    }

    static Instant epoch(JsonNode value, String field) {
        return Instant.ofEpochMilli(requiredLongValue(value, field));
    }

    private static JsonNode requireField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return value;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }

    private <T extends Enum<T>> List<T> enumList(Class<T> type, JsonNode node, String field) {
        List<T> values = new ArrayList<>();
        for (JsonNode item : requiredArray(node, field)) {
            values.add(enumValue(type, requiredTextValue(item, field), field));
        }
        return values;
    }
}
