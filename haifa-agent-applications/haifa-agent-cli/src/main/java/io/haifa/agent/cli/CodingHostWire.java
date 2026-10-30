package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingFollowUpReceipt;
import io.haifa.agent.application.project.product.coding.CodingModelControls;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelPreferences;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionCommandReceipt;
import io.haifa.agent.application.project.product.coding.CodingSessionExportResult;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.api.ClarificationPresentation;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionOption;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Explicit allowlist projection from product/Runtime values to the CA Host wire schema. */
final class CodingHostWire {
    private CodingHostWire() {}

    static Map<String, Object> cursor(RunEventCursor cursor) {
        Map<String, Object> value = map();
        value.put("runId", cursor.runId().value());
        value.put("feedVersion", cursor.feedVersion());
        value.put(
                "sequence",
                cursor.exclusiveSequence().isPresent()
                        ? cursor.exclusiveSequence().getAsLong()
                        : null);
        return value;
    }

    static Map<String, Object> summary(CodingSessionSummary summary) {
        Map<String, Object> value = map();
        value.put("sessionId", summary.sessionId().value());
        value.put("projectId", summary.projectId().value());
        value.put("displayName", summary.displayName());
        value.put("status", summary.status().name());
        value.put("activeRunId", summary.activeRunId().map(id -> id.value()).orElse(null));
        value.put("activeRunStatus", summary.activeRunStatus().map(Enum::name).orElse(null));
        value.put("queuedCount", summary.queuedCount());
        value.put("lastActivityAt", epoch(summary.lastActivityAt()));
        value.put("revision", summary.revision());
        return value;
    }

    static Map<String, Object> sessionView(CodingSessionView view, CodingSessionHistoryPage history) {
        Map<String, Object> value = map();
        value.put("session", summary(view.summary()));
        value.put("history", history(history));
        value.put(
                "activeRun",
                view.activeRun()
                        .map(run -> run(run, view.summary().sessionId()))
                        .orElse(null));
        value.put(
                "pendingInteraction",
                view.pendingInteraction().map(CodingHostWire::interaction).orElse(null));
        value.put("eventCursor", view.eventCursor().map(CodingHostWire::cursor).orElse(null));
        value.put("outputCursor", 0L);
        value.put("configurationDigest", view.configurationDigest());
        value.put("productProfileRef", view.productProfileRef());
        value.put("model", modelSelection(view.model()));
        value.put("activeRunTaskSummary", view.activeRunTaskSummary().orElse(null));
        value.put("previewAvailable", false);
        return value;
    }

    static Map<String, Object> run(AgentRunSnapshot run, io.haifa.agent.core.session.AgentSessionId sessionId) {
        Map<String, Object> value = map();
        value.put("runId", run.runId().value());
        value.put("sessionId", sessionId.value());
        value.put("status", run.status().name());
        value.put("version", run.version());
        value.put("updatedAt", epoch(run.updatedAt()));
        value.put("output", run.output().orElse(null));
        value.put("errorCode", run.error().map(error -> error.code().wireCode()).orElse(null));
        value.put("errorMessage", run.error().map(AgentError::message).orElse(null));
        value.put(
                "diagnosticId",
                run.error().flatMap(AgentError::optionalDiagnosticId).orElse(null));
        value.put(
                "terminationReason",
                run.terminationReason().map(CodingHostWire::termination).orElse(null));
        value.put("usage", usage(run));
        value.put("result", run.result().map(CodingHostWire::result).orElse(null));
        return value;
    }

    private static Map<String, Object> usage(AgentRunSnapshot run) {
        Map<String, Object> value = map();
        value.put("inputTokens", run.usage().inputTokens());
        value.put("outputTokens", run.usage().outputTokens());
        value.put("cachedInputTokens", run.usage().cachedInputTokens());
        value.put("modelCalls", run.usage().modelCalls());
        value.put("toolCalls", run.usage().toolCalls());
        value.put("childRuns", run.usage().childRuns());
        value.put("costMinorUnits", run.usage().costMinorUnits());
        value.put("wallTimeMillis", run.usage().wallTimeMillis());
        return value;
    }

    private static Map<String, Object> result(AgentRunResult result) {
        Map<String, Object> value = map();
        value.put("outcome", result.outcome().name());
        value.put("summary", result.summary());
        value.put("outputSchemaId", result.outputSchemaId());
        value.put("outputSchemaVersion", result.outputSchemaVersion());
        value.put("structuredOutput", jsonSafe(result.structuredOutput()));
        value.put(
                "artifacts",
                result.artifacts().stream().map(CodingHostWire::artifact).toList());
        value.put("warnings", result.warnings());
        return value;
    }

    private static Map<String, Object> artifact(io.haifa.agent.core.reference.ArtifactRef artifact) {
        Map<String, Object> value = map();
        value.put("artifactId", artifact.artifactId());
        value.put("artifactType", artifact.artifactType());
        value.put("version", artifact.version());
        value.put("title", artifact.title());
        return value;
    }

    private static Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) ? number : null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = map();
            map.forEach((key, item) -> safe.put(String.valueOf(key), jsonSafe(item)));
            return safe;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> safe = new ArrayList<>();
            iterable.forEach(item -> safe.add(jsonSafe(item)));
            return safe;
        }
        return null;
    }

    private static Map<String, Object> termination(RunTerminationReason reason) {
        Map<String, Object> value = map();
        value.put("code", reason.code());
        value.put("description", reason.description());
        return value;
    }

    static Map<String, Object> history(CodingSessionHistoryPage page) {
        Map<String, Object> value = map();
        value.put("sessionId", page.sessionId().value());
        List<Map<String, Object>> items = new ArrayList<>();
        for (CodingSessionHistoryItem item : page.items()) {
            Map<String, Object> entry = map();
            entry.put("id", item.id());
            entry.put("kind", item.kind().name());
            entry.put("title", item.title());
            entry.put("body", item.body());
            entry.put("status", item.status());
            entry.put("sequence", item.sequence());
            entry.put("createdAt", epoch(item.createdAt()));
            items.add(entry);
        }
        value.put("items", items);
        value.put("earlierHistoryAvailable", page.earlierHistoryAvailable());
        return value;
    }

    static Map<String, Object> queued(CodingQueuedMessage message) {
        Map<String, Object> value = map();
        value.put("followUpId", message.followUpId());
        value.put("sessionId", message.sessionId().value());
        value.put("summary", message.summary());
        value.put("sequence", message.sequence());
        value.put("revision", message.revision());
        return value;
    }

    static Map<String, Object> restored(CodingRestoredMessage message) {
        Map<String, Object> value = map();
        value.put("followUpId", message.followUpId());
        value.put("sessionId", message.sessionId().value());
        value.put("message", message.message());
        value.put(
                "attachments",
                message.attachments().stream().map(CodingHostWire::asset).toList());
        value.put("revision", message.revision());
        return value;
    }

    private static Map<String, Object> asset(AssetRef asset) {
        Map<String, Object> value = map();
        value.put("assetId", asset.assetId());
        value.put("mimeType", asset.mimeType());
        value.put("filename", asset.filename());
        return value;
    }

    static Map<String, Object> createReceipt(CodingSessionCommandReceipt receipt) {
        Map<String, Object> value = map();
        value.put("operation", receipt.operation());
        value.put("sessionId", receipt.sessionId().value());
        value.put("runId", receipt.runId().value());
        value.put("replayed", receipt.replayed());
        return value;
    }

    static Map<String, Object> followUp(CodingFollowUpReceipt receipt) {
        Map<String, Object> value = map();
        value.put("followUpId", receipt.followUpId());
        value.put("sessionId", receipt.sessionId().value());
        value.put("status", receipt.status().name());
        value.put("sequence", receipt.sequence());
        value.put("revision", receipt.revision());
        value.put(
                "dispatchedRunId",
                receipt.dispatchedRunId().map(id -> id.value()).orElse(null));
        return value;
    }

    static Map<String, Object> compaction(CodingCompactionResult result) {
        Map<String, Object> value = map();
        value.put("summaryReference", result.summaryReference().orElse(null));
        value.put("summaryVersion", result.summaryVersion());
        value.put("sourceMessageCount", result.sourceMessageCount());
        value.put("estimatedTokens", result.estimatedTokens());
        value.put("through", Map.of("value", result.through().value()));
        return value;
    }

    static Map<String, Object> export(CodingSessionExportResult result) {
        Map<String, Object> value = map();
        value.put("logicalPath", result.logicalPath());
        value.put("messageCount", result.messageCount());
        value.put("schemaVersion", result.schemaVersion());
        return value;
    }

    static Map<String, Object> shellPlan(CodingShellPlan plan) {
        Map<String, Object> value = map();
        value.put("token", plan.token());
        value.put("sessionId", plan.sessionId().value());
        value.put("safeCommand", plan.safeCommand());
        value.put("includeInContext", plan.includeInContext());
        value.put("state", plan.state().name());
        value.put("reasonCode", plan.reasonCode());
        return value;
    }

    static Map<String, Object> shellResult(CodingShellResult result) {
        Map<String, Object> value = map();
        value.put("processState", result.processState());
        value.put("exitCode", result.exitCode().orElse(null));
        value.put("safeSummary", result.safeSummary());
        value.put("outputReference", result.outputReference().orElse(null));
        value.put("truncated", result.truncated());
        value.put("includedInContext", result.includedInContext());
        return value;
    }

    static Map<String, Object> workspace(CodingWorkspaceView workspace) {
        Map<String, Object> value = map();
        value.put("workspaceRef", workspace.workspaceRef());
        value.put("safeDisplayName", workspace.safeDisplayName());
        value.put("mode", workspace.mode());
        value.put("status", workspace.status());
        value.put("revocable", workspace.revocable());
        return value;
    }

    static Map<String, Object> modelSelection(CodingModelSelection selection) {
        Map<String, Object> value = map();
        value.put("model", model(selection.model()));
        value.put("revision", selection.revision());
        value.put("available", selection.available());
        return value;
    }

    static Map<String, Object> model(CodingModelOption model) {
        Map<String, Object> value = map();
        value.put("id", model.id());
        value.put("displayName", model.displayName());
        value.put("providerId", model.providerId());
        value.put("providerDisplayName", model.providerDisplayName());
        value.put("capabilities", model.capabilities());
        value.put("contextWindow", model.contextWindow());
        value.put("maxOutputTokens", model.maxOutputTokens());
        value.put("state", modelState(model.state()));
        value.put("unavailableReason", model.unavailableReason());
        value.put("controls", controls(model.controls()));
        value.put("recommendedPreferences", preferences(model.recommendedPreferences()));
        value.put("imageInput", model.imageInput().isPresent());
        value.put(
                "imageInputProfile",
                model.imageInput().map(CodingHostWire::imageInput).orElse(null));
        return value;
    }

    private static Map<String, Object> imageInput(io.haifa.agent.model.api.ImageInputProfile profile) {
        Map<String, Object> value = map();
        value.put(
                "allowedSources",
                profile.allowedSources().stream().map(Enum::name).sorted().toList());
        value.put(
                "supportedMediaTypes",
                profile.supportedMediaTypes().stream().sorted().toList());
        value.put("maxImagesPerRequest", profile.maxImagesPerRequest());
        value.put("maxBytesPerItem", profile.maxBytesPerItem());
        value.put("maxTotalBytes", profile.maxTotalBytes());
        value.put("maxUrlCharacters", profile.maxUrlCharacters());
        value.put("detailSupported", profile.detailSupported());
        value.put(
                "allowedDetails",
                profile.allowedDetails().stream().map(Enum::name).sorted().toList());
        return value;
    }

    private static Map<String, Object> modelState(CodingModelState state) {
        Map<String, Object> value = map();
        value.put("connection", state.connection().name());
        value.put("bindingAvailability", state.bindingAvailability().name());
        value.put("runtime", state.runtime().name());
        value.put("runScope", state.runScope().name());
        return value;
    }

    private static Map<String, Object> controls(CodingModelControls controls) {
        Map<String, Object> value = map();
        value.put(
                "responseMode",
                Map.of(
                        "kind", controls.responseMode().kind(),
                        "visible", controls.responseMode().visible(),
                        "readOnly", controls.responseMode().readOnly(),
                        "allowedValues",
                                controls.responseMode().allowedValues().stream()
                                        .map(Enum::name)
                                        .toList(),
                        "recommendedValue",
                                controls.responseMode().recommendedValue().name(),
                        "effectiveSummary", controls.responseMode().effectiveSummary()));
        value.put(
                "reasoningEffort",
                Map.of(
                        "kind", controls.reasoningEffort().kind(),
                        "visible", controls.reasoningEffort().visible(),
                        "readOnly", controls.reasoningEffort().readOnly(),
                        "allowedValues",
                                controls.reasoningEffort().allowedValues().stream()
                                        .map(Enum::name)
                                        .toList(),
                        "recommendedValue",
                                controls.reasoningEffort().recommendedValue().name(),
                        "effectiveSummary", controls.reasoningEffort().effectiveSummary()));
        return value;
    }

    private static Map<String, Object> preferences(CodingModelPreferences preferences) {
        Map<String, Object> value = map();
        value.put("responseMode", preferences.responseMode().name());
        value.put("effort", preferences.effort().map(Enum::name).orElse(null));
        return value;
    }

    static Map<String, Object> interaction(InteractionView interaction) {
        Map<String, Object> value = map();
        value.put("interactionId", interaction.requestId().value());
        value.put("runId", interaction.runId().value());
        value.put("sessionId", interaction.sessionId().value());
        value.put("revision", interaction.revision());
        value.put("kind", interaction.kind().value());
        value.put("state", interaction.state().name());
        value.put("title", interaction.title());
        value.put("safePrompt", interaction.safePrompt());
        value.put(
                "allowedActions",
                interaction.allowedActions().stream()
                        .map(action -> action.value())
                        .toList());
        value.put("inputContract", inputContract(interaction.inputContract()));
        value.put("target", target(interaction));
        value.put("requester", requester(interaction));
        value.put("createdAt", epoch(interaction.createdAt()));
        value.put(
                "expiresAt", interaction.expiresAt().map(CodingHostWire::epoch).orElse(null));
        value.put(
                "consequences",
                Map.of(
                        "accepted", interaction.consequences().accepted(),
                        "rejected", interaction.consequences().rejected(),
                        "expired", interaction.consequences().expired()));
        value.put(
                "approvalPresentation",
                interaction.approvalPresentation().map(CodingHostWire::approval).orElse(null));
        value.put(
                "clarificationPresentation",
                interaction
                        .clarificationPresentation()
                        .map(CodingHostWire::clarification)
                        .orElse(null));
        return value;
    }

    private static Map<String, Object> inputContract(InteractionInputContract contract) {
        Map<String, Object> value = map();
        value.put("type", contract.type().value());
        value.put("maximumCharacters", contract.maximumCharacters());
        value.put("minimumSelections", contract.minimumSelections());
        value.put("maximumSelections", contract.maximumSelections());
        value.put("maximumParts", contract.maximumParts());
        value.put("maximumBytes", contract.maximumBytes());
        value.put(
                "options",
                contract.options().stream().map(CodingHostWire::option).toList());
        value.put("schemaRef", contract.schemaRef().orElse(null));
        return value;
    }

    private static Map<String, Object> option(InteractionOption option) {
        return Map.of("id", option.id(), "label", option.label());
    }

    private static Map<String, Object> target(InteractionView interaction) {
        Map<String, Object> value = map();
        value.put("type", interaction.target().type());
        value.put("reference", interaction.target().reference());
        value.put("version", interaction.target().version().orElse(null));
        value.put("digest", interaction.target().digest().orElse(null));
        value.put("safeSummary", interaction.target().safeSummary());
        return value;
    }

    private static Map<String, Object> requester(InteractionView interaction) {
        return Map.of(
                "principalType", interaction.requester().principalType(),
                "displayLabel", interaction.requester().displayLabel());
    }

    private static Map<String, Object> approval(ApprovalPresentation approval) {
        Map<String, Object> value = map();
        value.put("title", approval.title());
        value.put("purpose", approval.purpose());
        value.put("contentType", approval.contentType());
        value.put("content", approval.content());
        value.put("environment", facts(approval.environment()));
        value.put("technical", facts(approval.technical()));
        value.put("risk", approval.risk().orElse(null));
        return value;
    }

    private static List<Map<String, Object>> facts(List<ApprovalPresentation.Fact> facts) {
        return facts.stream()
                .map(fact -> Map.<String, Object>of("label", fact.label(), "value", fact.value()))
                .toList();
    }

    private static Map<String, Object> clarification(ClarificationPresentation clarification) {
        Map<String, Object> value = map();
        value.put("title", clarification.title());
        value.put(
                "questions",
                clarification.questions().stream()
                        .map(question -> Map.<String, Object>of(
                                "header",
                                question.header(),
                                "question",
                                question.question(),
                                "multiple",
                                question.multiple(),
                                "allowCustom",
                                question.allowCustom(),
                                "options",
                                question.options().stream()
                                        .map(option -> {
                                            Map<String, Object> item = map();
                                            item.put("optionId", option.optionId());
                                            item.put("label", option.label());
                                            item.put(
                                                    "description",
                                                    option.description().orElse(null));
                                            return item;
                                        })
                                        .toList()))
                        .toList());
        return value;
    }

    static Map<String, Object> event(AgentRunEvent event) {
        Map<String, Object> value = map();
        value.put("eventId", event.eventId());
        value.put("eventType", event.eventType());
        value.put("eventSchemaVersion", event.eventSchemaVersion());
        value.put("runId", event.runId().value());
        value.put("sessionId", event.sessionId().value());
        value.put("sequence", event.sequence());
        value.put("cursor", cursor(event.cursor()));
        value.put("occurredAt", epoch(event.occurredAt()));
        value.put("correlationId", event.correlationId().orElse(null));
        value.put("causationId", event.causationId().orElse(null));
        Optional<Map<String, Object>> payload = payload(event.payload());
        value.put("payload", payload.orElse(null));
        value.put("payloadUnsupported", payload.isEmpty());
        return value;
    }

    static Map<String, Object> eventPage(RunEventPage page) {
        Map<String, Object> value = map();
        value.put("items", page.items().stream().map(CodingHostWire::event).toList());
        value.put("nextCursor", cursor(page.nextCursor()));
        value.put("headCursor", cursor(page.headCursor()));
        value.put("hasMore", page.hasMore());
        return value;
    }

    static Map<String, Object> output(AgentRunOutputEvent event, io.haifa.agent.core.session.AgentSessionId sessionId) {
        Map<String, Object> value = map();
        value.put("runId", event.runId().value());
        value.put("sessionId", sessionId.value());
        value.put("modelCallId", event.modelCallId());
        value.put("generationId", event.generationId());
        value.put("physicalAttempt", event.physicalAttempt());
        value.put("sequence", event.sequence());
        value.put("type", event.type().name());
        value.put("textDelta", event.textDelta());
        value.put("occurredAt", epoch(event.occurredAt()));
        return value;
    }

    static Map<String, Object> authStatus(
            boolean connectionRequired,
            String apiKeyProviderId,
            boolean apiKeyConnectionSupported,
            List<CodingAuthenticationProviderView> providers,
            List<CodingAuthenticationView> connections,
            boolean logoutSupported) {
        Map<String, Object> value = map();
        value.put("connectionRequired", connectionRequired);
        value.put("apiKeyProviderId", apiKeyProviderId);
        value.put("apiKeyConnectionSupported", apiKeyConnectionSupported);
        value.put(
                "providers",
                providers.stream().map(CodingHostWire::authProvider).toList());
        value.put(
                "connections",
                connections.stream().map(CodingHostWire::authConnection).toList());
        value.put("credentialWrite", false);
        value.put("credentialLogin", false);
        value.put("logout", logoutSupported);
        return value;
    }

    private static Map<String, Object> authProvider(CodingAuthenticationProviderView provider) {
        Map<String, Object> value = map();
        value.put("providerId", provider.providerId());
        value.put("displayName", provider.displayName());
        value.put("category", provider.category().name());
        value.put("connected", provider.connected());
        value.put("recommended", provider.recommended());
        value.put("current", provider.current());
        value.put("accountLabel", provider.accountLabel().orElse(null));
        value.put("planLabel", provider.planLabel().orElse(null));
        value.put(
                "methods",
                provider.methods().stream()
                        .map(method -> {
                            Map<String, Object> item = map();
                            item.put("id", method.id());
                            item.put("type", method.type().name());
                            item.put("available", method.available());
                            item.put(
                                    "unavailableReason",
                                    method.unavailableReason().orElse(null));
                            return item;
                        })
                        .toList());
        value.put("unavailableReason", provider.unavailableReason().orElse(null));
        return value;
    }

    private static Map<String, Object> authConnection(CodingAuthenticationView connection) {
        Map<String, Object> value = map();
        value.put("connectionId", connection.connectionId());
        value.put("providerId", connection.providerId());
        value.put("method", connection.method().name());
        value.put("status", connection.status().name());
        value.put("accountLabel", connection.accountLabel());
        value.put("planType", connection.planType().orElse(null));
        value.put(
                "resetAtEpochMillis",
                connection.resetAtEpochMillis().isPresent()
                        ? connection.resetAtEpochMillis().getAsLong()
                        : null);
        value.put("unofficialLocalCompatibility", connection.unofficialLocalCompatibility());
        return value;
    }

    private static Optional<Map<String, Object>> payload(AgentRunEvent.Payload payload) {
        if (payload instanceof RunEventPayloads.MessageCommitted value) {
            return Optional.of(Map.of(
                    "kind",
                    "message-committed",
                    "messageId",
                    value.messageId(),
                    "messageSequence",
                    value.messageSequence()));
        }
        if (payload instanceof RunEventPayloads.RunLifecycle value) {
            Map<String, Object> result = map();
            result.put("kind", "run-lifecycle");
            result.put("status", value.status());
            result.put("version", value.version());
            result.put("reasonCode", value.reasonCode());
            result.put("errorMessage", value.errorMessage().orElse(null));
            result.put("diagnosticId", value.diagnosticId().orElse(null));
            return Optional.of(result);
        }
        if (payload instanceof RunEventPayloads.AssistantOutput value) {
            return Optional.of(Map.of(
                    "kind", "assistant-output",
                    "generationId", value.generationId(),
                    "status", value.status(),
                    "referenceOrSafeCode", value.referenceOrSafeCode()));
        }
        if (payload instanceof RunEventPayloads.AssistantTextDelta value) {
            return Optional.of(Map.of(
                    "kind", "assistant-text-delta",
                    "generationId", value.generationId(),
                    "textDelta", value.textDelta()));
        }
        if (payload instanceof RunEventPayloads.InteractionLifecycle value) {
            return Optional.of(Map.of(
                    "kind", "interaction-lifecycle",
                    "requestId", value.requestId(),
                    "interactionKind", value.kind(),
                    "state", value.state(),
                    "actionOrReason", value.actionOrReason()));
        }
        if (payload instanceof RunEventPayloads.RunInputLifecycle value) {
            return Optional.of(Map.of(
                    "kind", "run-input-lifecycle",
                    "inputId", value.inputId(),
                    "state", value.state(),
                    "applicationCoordinate", value.applicationCoordinate()));
        }
        if (payload instanceof RunEventPayloads.CommandResult value) {
            return Optional.of(Map.of(
                    "kind", "command-result",
                    "commandId", value.commandId(),
                    "commandType", value.commandType(),
                    "status", value.status()));
        }
        if (payload instanceof RunEventPayloads.ModelLifecycle value) {
            Map<String, Object> result = map();
            result.put("kind", "model-lifecycle");
            result.put("modelCallId", value.modelCallId());
            result.put("providerId", value.providerId());
            result.put("modelId", value.modelId());
            result.put("status", value.status());
            result.put("iteration", value.iteration());
            result.put("attempt", value.attempt());
            result.put("inputTokens", value.inputTokens());
            result.put("outputTokens", value.outputTokens());
            result.put("finishReason", value.finishReason());
            result.put("reasonCode", value.reasonCode());
            return Optional.of(result);
        }
        if (payload instanceof RunEventPayloads.ModelAttemptLifecycle value) {
            Map<String, Object> result = map();
            result.put("kind", "model-attempt-lifecycle");
            result.put("modelRequestId", value.modelRequestId());
            result.put("modelCallId", value.modelCallId().isEmpty() ? null : value.modelCallId());
            result.put("status", value.status());
            result.put("attempt", value.attempt());
            result.put("delayMillis", value.delayMillis());
            result.put("reasonCode", value.reasonCode());
            return Optional.of(result);
        }
        if (payload instanceof RunEventPayloads.ToolLifecycle value) {
            Map<String, Object> result = map();
            result.put("kind", "tool-lifecycle");
            result.put("toolCallId", value.toolCallId());
            result.put("displayName", value.displayName());
            result.put("status", value.status());
            result.put("reasonCode", value.reasonCode());
            result.put("targetSummary", value.targetSummary());
            result.put("resultRef", value.resultRef());
            result.put("intentSummary", value.intentSummary());
            result.put("toolName", value.toolName());
            result.put(
                    "observation",
                    value.observation().map(CodingHostWire::observation).orElse(null));
            return Optional.of(result);
        }
        if (payload instanceof RunEventPayloads.ResourceAvailable value) {
            return Optional.of(Map.of(
                    "kind", "resource-available",
                    "reference", value.reference(),
                    "resourceKind", value.kind(),
                    "title", value.title(),
                    "status", value.status(),
                    "action", value.action()));
        }
        if (payload instanceof RunEventPayloads.DeliveryLifecycle value) {
            Map<String, Object> result = map();
            result.put("kind", "delivery-lifecycle");
            result.put("phase", value.phase());
            result.put("status", value.status());
            result.put("reasonCode", value.reasonCode());
            result.put("missingEvidence", value.missingEvidence());
            result.put("remainingPercent", value.remainingPercent());
            result.put("attempt", value.attempt());
            result.put("limitingResource", value.limitingResource());
            result.put("limitingUsed", value.limitingUsed());
            result.put("limitingLimit", value.limitingLimit());
            return Optional.of(result);
        }
        if (payload instanceof RunEventPayloads.ChildRunLifecycle value) {
            return Optional.of(Map.of(
                    "kind", "child-run-lifecycle",
                    "childRunId", value.childRunId(),
                    "toolCallId", value.toolCallId(),
                    "childAgent", value.childAgent(),
                    "status", value.status(),
                    "reasonCode", value.reasonCode()));
        }
        return Optional.empty();
    }

    private static Map<String, Object> observation(RunEventPayloads.ToolObservation observation) {
        Map<String, Object> value = map();
        value.put(
                "outputPreview",
                observation.outputPreview().map(CodingHostWire::boundedText).orElse(null));
        value.put("processState", observation.processState().orElse(null));
        value.put("exitCode", observation.exitCode().orElse(null));
        return value;
    }

    private static Map<String, Object> boundedText(io.haifa.agent.runtime.api.display.BoundedText text) {
        Map<String, Object> value = map();
        value.put("text", text.text());
        value.put("truncated", text.truncated());
        value.put("byteCount", text.byteCount());
        value.put("lineCount", text.lineCount());
        value.put("truncationReason", text.truncationReason().map(Enum::name).orElse(null));
        return value;
    }

    private static long epoch(Instant instant) {
        return instant.toEpochMilli();
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    static OptionalLong outputSequence(Integer value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value.longValue());
    }
}
