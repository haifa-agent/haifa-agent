package io.haifa.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingFollowUpReceipt;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunOutcomeProjection;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunOutcomeProjectionService;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeContractException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** JSON-RPC command dispatcher over the existing Coding product facade. */
final class CodingHostDispatcher {
    private static final String PROTOCOL_VERSION = "1";

    private final ProjectId projectId;
    private final CodingSessionClient client;
    private final CodingSessionService sessions;
    private final AgentRuntime runtime;
    private final CodingRunOutcomeProjectionService outcomes;
    private final CodingAuthenticationClient authentication;
    private final CodingHostSubscriptions subscriptions;

    CodingHostDispatcher(
            ProjectId projectId,
            CodingSessionClient client,
            CodingSessionService sessions,
            AgentRuntime runtime,
            CodingRunOutcomeProjectionService outcomes,
            CodingAuthenticationClient authentication,
            CodingHostSubscriptions subscriptions) {
        this.projectId = projectId;
        this.client = client;
        this.sessions = sessions;
        this.runtime = runtime;
        this.outcomes = outcomes;
        this.authentication = authentication;
        this.subscriptions = subscriptions;
    }

    Object dispatch(String clientId, String method, JsonNode params) {
        JsonNode safeParams =
                params == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : params;
        if (!safeParams.isObject()) throw CodingHostException.invalidParams("params must be an object");
        try {
            if (method.equals("initialize")) return initialize(safeParams);
            if (method.equals("host/ping")) return ping();
            return switch (method) {
                case "session/list" -> listSessions(safeParams);
                case "session/create" -> createSession(safeParams);
                case "session/open" -> sessionOpen(safeParams);
                case "session/reconcile" -> sessionView(client.reconcile(session(safeParams)));
                case "session/history" ->
                    CodingHostWire.history(client.history(session(safeParams), limit(safeParams, 100, 200)));
                case "session/rename" ->
                    CodingHostWire.summary(client.rename(
                            session(safeParams),
                            text(safeParams, "displayName"),
                            requiredLong(safeParams, "expectedRevision")));
                case "session/archive" ->
                    CodingHostWire.summary(
                            client.archive(session(safeParams), requiredLong(safeParams, "expectedRevision")));
                case "session/delete" -> {
                    client.delete(session(safeParams), requiredLong(safeParams, "expectedRevision"));
                    yield Map.of("deleted", true);
                }
                case "session/compact" ->
                    CodingHostWire.compaction(client.compact(
                            session(safeParams),
                            optionalText(safeParams, "instruction").orElse("")));
                case "session/export" ->
                    CodingHostWire.export(client.export(session(safeParams), text(safeParams, "logicalDestination")));
                case "session/ackCursor" ->
                    CodingHostWire.cursor(client.acknowledgeCursor(
                            session(safeParams), cursorForSession(safeParams.get("cursor"), session(safeParams))));
                case "session/selectModel" ->
                    CodingHostWire.modelSelection(client.selectModel(
                            session(safeParams),
                            text(safeParams, "modelId"),
                            requiredLong(safeParams, "expectedRevision"),
                            text(safeParams, "idempotencyKey")));
                case "session/queuedMessages" ->
                    Map.of(
                            "items",
                            client.restorableMessages(session(safeParams), limit(safeParams, 100, 100)).stream()
                                    .map(CodingHostWire::queued)
                                    .toList());
                case "session/restoreQueued" ->
                    CodingHostWire.restored(client.restore(
                            session(safeParams),
                            text(safeParams, "followUpId"),
                            requiredLong(safeParams, "expectedRevision")));
                case "run/start" -> runStart(safeParams);
                case "run/steer" -> steer(safeParams);
                case "run/followUp" -> followUp(safeParams);
                case "run/cancel" -> cancel(safeParams);
                case "run/get" -> run(safeParams);
                case "run/outcome" -> outcome(safeParams);
                case "interaction/get" ->
                    client.pendingInteraction(runId(safeParams))
                            .map(CodingHostWire::interaction)
                            .orElse(null);
                case "interaction/respond" -> respond(safeParams);
                case "approval/prepare" -> approvalPrepare(safeParams);
                case "approval/allowForSession", "approval/resetSession" ->
                    throw CodingHostException.unsupported("Remote session consent is unavailable");
                case "events/page" -> eventsPage(safeParams);
                case "events/subscribe" -> subscribeEvents(clientId, safeParams);
                case "events/unsubscribe", "output/unsubscribe" ->
                    Map.of("closed", subscriptions.unsubscribe(clientId, text(safeParams, "subscriptionId")));
                case "output/page" -> outputPage(safeParams);
                case "output/subscribe" -> subscribeOutput(clientId, safeParams);
                case "preview/subscribe" -> throw CodingHostException.unsupported("Tool preview is unavailable");
                case "model/list" ->
                    Map.of(
                            "models",
                            client.models().stream().map(CodingHostWire::model).toList());
                case "shell/plan" ->
                    CodingHostWire.shellPlan(client.planShell(
                            session(safeParams), text(safeParams, "command"), bool(safeParams, "includeInContext")));
                case "shell/execute" ->
                    CodingHostWire.shellResult(
                            client.executeShell(text(safeParams, "token"), bool(safeParams, "approved")));
                case "shell/discard" -> {
                    client.discardShell(text(safeParams, "token"));
                    yield Map.of("discarded", true);
                }
                case "workspace/paths" -> Map.of("paths", client.logicalPaths());
                case "workspace/list" ->
                    Map.of(
                            "workspaces",
                            client.workspaces().stream()
                                    .map(CodingHostWire::workspace)
                                    .toList());
                case "workspace/revoke" -> {
                    client.revokeWorkspace(text(safeParams, "workspaceRef"));
                    yield Map.of("revoked", true);
                }
                case "resources/loaded" -> Map.of("resources", client.loadedResources());
                case "resources/reload" -> Map.of("resources", client.reloadResources());
                case "auth/status" -> authStatus();
                case "auth/logout" -> Map.of("removed", authentication.logout(text(safeParams, "connectionId")));
                case "auth/saveApiKey", "auth/login", "auth/loginCodex", "auth/loginAntigravity" ->
                    throw CodingHostException.unsupported("Remote authentication mutations are unavailable");
                default -> throw CodingHostException.unknownMethod(method);
            };
        } catch (CodingHostException exception) {
            throw exception;
        } catch (ProjectProductException exception) {
            throw CodingHostException.conflict(exception.code(), "Coding product rejected the command");
        } catch (RuntimeContractException exception) {
            throw CodingHostException.conflict(exception.code().name(), "Runtime rejected the command");
        } catch (UnsupportedOperationException exception) {
            throw CodingHostException.unsupported("Remote method is unavailable");
        } catch (IllegalArgumentException exception) {
            throw CodingHostException.invalidParams("Invalid parameters");
        } catch (IllegalStateException exception) {
            throw CodingHostException.conflict("OPERATION_REJECTED", "Host rejected the command");
        } catch (RuntimeException exception) {
            throw CodingHostException.internal("Host command failed safely");
        }
    }

    private Map<String, Object> initialize(JsonNode params) {
        String version = optionalText(params, "protocolVersion").orElse("");
        if (!PROTOCOL_VERSION.equals(version)) {
            throw CodingHostException.conflict("CONTRACT_VERSION_UNSUPPORTED", "Unsupported protocol version");
        }
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("session-list", true);
        capabilities.put("session-create", true);
        capabilities.put("session-open", true);
        capabilities.put("session-history", true);
        capabilities.put("run-start", true);
        capabilities.put("run-steer", true);
        capabilities.put("run-follow-up", true);
        capabilities.put("run-cancel", true);
        capabilities.put("interaction", true);
        capabilities.put("event-stream", true);
        capabilities.put("output-stream", true);
        capabilities.put("shell", true);
        capabilities.put("export", true);
        capabilities.put("models", true);
        capabilities.put("workspace", true);
        capabilities.put("resources", true);
        capabilities.put("auth-status", true);
        capabilities.put("logout", true);
        capabilities.put("preview", false);
        capabilities.put("session-consent", false);
        capabilities.put("auth-mutation", false);
        return Map.of(
                "protocolVersion",
                PROTOCOL_VERSION,
                "hostName",
                "ca-host",
                "projectId",
                projectId.value(),
                "operator",
                Map.of("principalType", "local-user"),
                "capabilities",
                capabilities,
                "unsupported",
                List.of("preview", "session-consent", "credential-write", "credential-login"));
    }

    private Map<String, Object> ping() {
        return Map.of("status", "ok", "version", PROTOCOL_VERSION);
    }

    private Map<String, Object> listSessions(JsonNode params) {
        int limit = limit(params, 50, 100);
        Optional<String> search = optionalText(params, "search");
        var page = sessions.listSessions(
                projectId,
                new io.haifa.agent.application.project.product.coding.CodingSessionQuery(
                        search, Optional.empty(), limit));
        return Map.of(
                "sessions",
                page.items().stream().map(CodingHostWire::summary).toList(),
                "truncated",
                page.hasMore(),
                "limit",
                limit);
    }

    private Map<String, Object> createSession(JsonNode params) {
        CodingSessionCreateOptions options = optionalText(params, "modelId")
                .map(CodingSessionCreateOptions::withInitialModel)
                .orElseGet(CodingSessionCreateOptions::defaults);
        CodingSessionView view = sessions.createSession(
                projectId, text(params, "text"), List.of(), text(params, "idempotencyKey"), options);
        return sessionView(view);
    }

    private void requireHostSession(AgentSessionId sessionId) {
        sessions.requireSessionInProject(sessionId, projectId);
    }

    private Map<String, Object> sessionOpen(JsonNode params) {
        return sessionView(client.open(session(params)));
    }

    private Map<String, Object> sessionView(CodingSessionView view) {
        return CodingHostWire.sessionView(view, client.history(view.summary().sessionId(), 100));
    }

    private Map<String, Object> steer(JsonNode params) {
        RunInputReceipt receipt = steerReceipt(params);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("inputId", receipt.inputId().value());
        value.put("runId", receipt.runId().value());
        value.put("status", receipt.status().name());
        value.put("acceptedAt", receipt.acceptedAt().toEpochMilli());
        value.put(
                "appliedAt",
                receipt.appliedAt().map(java.time.Instant::toEpochMilli).orElse(null));
        value.put("reasonCode", receipt.reasonCode().orElse(null));
        return value;
    }

    private Map<String, Object> runStart(JsonNode params) {
        AgentSessionId sessionId = session(params);
        requireHostSession(sessionId);
        return CodingHostWire.createReceipt(
                sessions.submitTurn(sessionId, text(params, "text"), List.of(), text(params, "idempotencyKey")));
    }

    private RunInputReceipt steerReceipt(JsonNode params) {
        AgentSessionId sessionId = session(params);
        requireHostSession(sessionId);
        return sessions.steer(sessionId, runId(params), text(params, "text"), text(params, "idempotencyKey"));
    }

    private Map<String, Object> followUp(JsonNode params) {
        AgentSessionId sessionId = session(params);
        requireHostSession(sessionId);
        CodingFollowUpReceipt receipt = sessions.enqueueFollowUp(
                sessionId, runId(params), text(params, "text"), List.of(), text(params, "idempotencyKey"));
        return CodingHostWire.followUp(receipt);
    }

    private Map<String, Object> cancel(JsonNode params) {
        AgentSessionId sessionId = session(params);
        requireHostSession(sessionId);
        RuntimeCommandResult result = sessions.abortActiveRun(sessionId, runId(params), text(params, "idempotencyKey"))
                .orElseThrow(() -> CodingHostException.conflict("ACTIVE_RUN_MISMATCH", "No active Run"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("commandId", result.command().commandId().value());
        value.put("runId", result.command().runId().value());
        value.put("status", result.status().name());
        value.put("runVersion", result.snapshot().version());
        return value;
    }

    private Map<String, Object> run(JsonNode params) {
        AgentRunId runId = runId(params);
        AgentRunSnapshot snapshot = client.findRun(runId)
                .orElseThrow(() -> CodingHostException.notFound("RUN_NOT_FOUND", "Run is unavailable"));
        AgentSessionId sessionId = runtime.view(runId)
                .map(view -> view.sessionId())
                .orElseThrow(
                        () -> CodingHostException.notFound("SESSION_NOT_FOUND", "Run and Session are unavailable"));
        return CodingHostWire.run(snapshot, sessionId);
    }

    private Map<String, Object> outcome(JsonNode params) {
        CodingRunOutcomeProjection outcome = client.findOutcome(runId(params))
                .orElseThrow(() -> CodingHostException.notFound("RUN_NOT_FOUND", "Run is unavailable"));
        return Map.of(
                "runId", outcome.runId().value(),
                "protocolStatus", outcome.protocolStatus().name(),
                "diagnosticCodes", outcome.diagnosticCodes());
    }

    private Map<String, Object> respond(JsonNode params) {
        AgentRunId runId = runId(params);
        AgentSessionId sessionId = session(params);
        String interactionId = text(params, "interactionId");
        long revision = requiredLong(params, "revision");
        InteractionView pending = client.pendingInteraction(runId)
                .orElseThrow(() -> CodingHostException.notFound("INTERACTION_NOT_FOUND", "Interaction is unavailable"));
        if (!pending.requestId().value().equals(interactionId)
                || !pending.runId().equals(runId)
                || !pending.sessionId().equals(sessionId)
                || pending.revision() != revision) {
            throw CodingHostException.conflict("INTERACTION_REVISION_CONFLICT", "Interaction identity is stale");
        }
        InteractionAction action = new InteractionAction(text(params, "action"));
        String key = text(params, "idempotencyKey");
        List<ContentPart> inputs = inputs(params.get("inputs"));
        InteractionResponseReceipt receipt =
                inputs.isEmpty() ? client.respond(pending, action, key) : client.respond(pending, action, inputs, key);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("responseId", receipt.responseId().value());
        value.put("requestId", receipt.requestId().value());
        value.put("runId", receipt.runId().value());
        value.put("status", receipt.status().name());
        value.put("interactionState", receipt.interactionState().name());
        value.put("revision", receipt.revision());
        value.put("runVersion", receipt.runVersion());
        return value;
    }

    private Map<String, Object> approvalPrepare(JsonNode params) {
        return client.pendingInteraction(runId(params))
                .map(interaction -> Map.<String, Object>of(
                        "interaction", CodingHostWire.interaction(interaction), "allowForSession", false))
                .orElse(null);
    }

    private Map<String, Object> eventsPage(JsonNode params) {
        AgentSessionId sessionId = session(params);
        AgentRunId runId = runId(params);
        requireRunSession(sessionId, runId);
        RunEventCursor after = optionalCursor(params.get("afterCursor"), runId);
        RunEventPage page = client.events(runId, after, limit(params, 200, 500));
        return CodingHostWire.eventPage(page);
    }

    private Map<String, Object> subscribeEvents(String clientId, JsonNode params) {
        AgentSessionId sessionId = session(params);
        AgentRunId runId = runId(params);
        requireRunSession(sessionId, runId);
        RunEventCursor after = optionalCursor(params.get("afterCursor"), runId);
        String id = subscriptions.subscribeEvents(clientId, sessionId, runId, after);
        return Map.of(
                "subscriptionId", id, "sessionId", sessionId.value(), "runId", runId.value(), "type", "run-event");
    }

    private Map<String, Object> outputPage(JsonNode params) {
        AgentSessionId sessionId = session(params);
        AgentRunId runId = runId(params);
        requireRunSession(sessionId, runId);
        long after = optionalLong(params, "afterCursor").orElse(0L);
        List<AgentRunOutputEvent> items =
                runtime.outputEvents(runId, new RunOutputCursor(after), limit(params, 200, 500));
        long next = items.isEmpty() ? after : items.getLast().sequence();
        return Map.of(
                "items",
                items.stream()
                        .map(event -> CodingHostWire.output(event, sessionId))
                        .toList(),
                "nextCursor",
                next);
    }

    private Map<String, Object> subscribeOutput(String clientId, JsonNode params) {
        AgentSessionId sessionId = session(params);
        AgentRunId runId = runId(params);
        requireRunSession(sessionId, runId);
        long after = optionalLong(params, "afterCursor").orElse(0L);
        String id = subscriptions.subscribeOutput(clientId, sessionId, runId, new RunOutputCursor(after));
        return Map.of("subscriptionId", id, "sessionId", sessionId.value(), "runId", runId.value(), "type", "output");
    }

    private Map<String, Object> authStatus() {
        return CodingHostWire.authStatus(
                authentication.connectionRequired(),
                authentication.apiKeyProviderId(),
                authentication.apiKeyConnectionSupported(),
                authentication.providers(),
                authentication.connections(),
                true);
    }

    private AgentSessionId session(JsonNode params) {
        return new AgentSessionId(text(params, "sessionId"));
    }

    private AgentRunId runId(JsonNode params) {
        return new AgentRunId(text(params, "runId"));
    }

    private RunEventCursor cursorForSession(JsonNode node, AgentSessionId sessionId) {
        if (node == null || node.isNull()) {
            throw CodingHostException.invalidParams("cursor is required");
        }
        AgentRunId runId = new AgentRunId(text(node, "runId"));
        requireRunSession(sessionId, runId);
        return cursor(node, runId);
    }

    private void requireRunSession(AgentSessionId sessionId, AgentRunId runId) {
        runtime.view(runId)
                .filter(view -> view.sessionId().equals(sessionId))
                .orElseThrow(
                        () -> CodingHostException.notFound("SESSION_NOT_FOUND", "Run and Session are unavailable"));
    }

    private RunEventCursor optionalCursor(JsonNode node, AgentRunId runId) {
        return node == null || node.isNull() ? RunEventCursor.beforeFirst(runId) : cursor(node, runId);
    }

    private RunEventCursor cursor(JsonNode node, AgentRunId runId) {
        if (node == null || node.isNull()) return RunEventCursor.beforeFirst(runId);
        String cursorRun = text(node, "runId");
        if (!cursorRun.equals(runId.value())) {
            throw CodingHostException.conflict("EVENT_CURSOR_RUN_MISMATCH", "Cursor belongs to another Run");
        }
        String feedVersion = optionalText(node, "feedVersion").orElse("1");
        OptionalLong sequence = optionalLong(node, "sequence");
        if (sequence.isPresent() && sequence.getAsLong() < 1) {
            throw CodingHostException.invalidParams("cursor sequence must be positive");
        }
        return new RunEventCursor(
                runId,
                feedVersion,
                sequence.isPresent() ? OptionalLong.of(sequence.getAsLong()) : OptionalLong.empty());
    }

    private List<ContentPart> inputs(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw CodingHostException.invalidParams("inputs must be an array");
        List<ContentPart> result = new ArrayList<>();
        for (JsonNode item : node) {
            String type = text(item, "type");
            if (!type.equals("text")) throw CodingHostException.invalidParams("unsupported input type");
            result.add(new TextPart(
                    text(item, "text"), optionalText(item, "format").orElse("text/plain")));
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) throw CodingHostException.invalidParams(field + " is required");
        JsonNode value = node.get(field);
        if (!value.isTextual()) throw CodingHostException.invalidParams(field + " must be text");
        String result = value.textValue().trim();
        if (result.isEmpty()) throw CodingHostException.invalidParams(field + " must not be blank");
        return result;
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return Optional.empty();
        JsonNode value = node.get(field);
        if (!value.isTextual()) throw CodingHostException.invalidParams(field + " must be text");
        String result = value.textValue().trim();
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private static long requiredLong(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) throw CodingHostException.invalidParams(field + " is required");
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw CodingHostException.invalidParams(field + " must be an integer");
        }
        return value.longValue();
    }

    private static OptionalLong optionalLong(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return OptionalLong.empty();
        long value = requiredLong(node, field);
        return OptionalLong.of(value);
    }

    private static int limit(JsonNode node, int defaultValue, int maximum) {
        if (node == null || !node.hasNonNull("limit")) return defaultValue;
        JsonNode value = node.get("limit");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw CodingHostException.invalidParams("limit must be an integer");
        }
        int result = value.intValue();
        if (result < 1 || result > maximum) throw CodingHostException.invalidParams("limit is out of range");
        return result;
    }

    private static boolean bool(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) throw CodingHostException.invalidParams(field + " is required");
        JsonNode value = node.get(field);
        if (!value.isBoolean()) throw CodingHostException.invalidParams(field + " must be boolean");
        return value.booleanValue();
    }
}
