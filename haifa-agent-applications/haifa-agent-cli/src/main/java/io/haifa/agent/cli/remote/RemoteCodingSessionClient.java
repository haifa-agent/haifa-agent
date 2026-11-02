package io.haifa.agent.cli.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingCompactionResult;
import io.haifa.agent.application.project.product.coding.CodingFollowUpStatus;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingRestoredMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionExportResult;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import io.haifa.agent.application.project.product.coding.client.CodingApprovalPrompt;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunOutcomeProjection;
import io.haifa.agent.application.project.product.coding.delivery.CodingRunProtocolStatus;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Remote Coding Agent product client over the loopback CA Host command and SSE protocol.
 *
 * <p>The adapter owns only its transport observations. It never cancels a Run, never retries an uncertain
 * mutation, and never acknowledges an event cursor on its own.
 */
public final class RemoteCodingSessionClient implements CodingSessionClient, AutoCloseable {
    private static final String PROTOCOL_VERSION = "1";
    private static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "session-list",
            "session-create",
            "session-open",
            "session-history",
            "run-start",
            "run-steer",
            "run-follow-up",
            "run-cancel",
            "interaction",
            "event-stream",
            "output-stream",
            "shell",
            "export",
            "models",
            "workspace",
            "resources",
            "auth-status",
            "logout");
    private static final Set<String> FORBIDDEN_CAPABILITIES = Set.of("preview", "session-consent", "auth-mutation");

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RemoteRpcTransport transport;
    private final RemoteWireCodec codec;
    private final CodingAuthenticationClient authentication;
    private final Map<AgentRunId, AgentSessionId> runSessions = new ConcurrentHashMap<>();
    private final Set<RemoteSseSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final Map<AgentSessionId, ProjectProductException> observationFailures = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private volatile RemoteCodingClientInfo info;
    private volatile ProjectId projectId;
    private volatile boolean closed;

    private RemoteCodingSessionClient(URI baseUri) {
        this.transport = new RemoteRpcTransport(baseUri, json, executor);
        this.codec = new RemoteWireCodec(json);
        this.authentication = new RemoteCodingAuthenticationClient(transport, codec);
    }

    /** Connects to one loopback CA Host and initializes protocol 1. */
    public static RemoteCodingSessionClient connect(URI endpoint) {
        return connect(endpoint, "haifa-ca-remote-client", "1");
    }

    /** Connects to one loopback CA Host with explicit client identity for initialize. */
    public static RemoteCodingSessionClient connect(URI endpoint, String clientName, String clientVersion) {
        URI baseUri = RemoteHostUri.requireLoopbackBase(endpoint);
        String safeName = requireClientText(clientName, "clientName");
        String safeVersion = requireClientText(clientVersion, "clientVersion");
        RemoteCodingSessionClient client = new RemoteCodingSessionClient(baseUri);
        try {
            client.initialize(safeName, safeVersion);
            return client;
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    public ProjectId projectId() {
        return projectId;
    }

    public RemoteCodingClientInfo info() {
        return info;
    }

    public CodingAuthenticationClient authentication() {
        return authentication;
    }

    private void initialize(String clientName, String clientVersion) {
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("clientInfo", clientInfo);
        JsonNode result = transport.call("initialize", params, false);
        decode(() -> {
            RemoteWireCodec.requireObject(result, "initialize result");
            String version = RemoteWireCodec.requiredText(result, "protocolVersion");
            if (!PROTOCOL_VERSION.equals(version)) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            String clientId = RemoteWireCodec.requiredText(result, "clientId");
            ProjectId hostProject = new ProjectId(RemoteWireCodec.requiredText(result, "projectId"));
            String hostName = RemoteWireCodec.requiredText(result, "hostName");
            JsonNode operator = RemoteWireCodec.requiredObject(result, "operator");
            String principalType = RemoteWireCodec.requiredText(operator, "principalType");
            JsonNode capabilityNode = RemoteWireCodec.requiredObject(result, "capabilities");
            Set<String> supported = new TreeSet<>();
            capabilityNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isBoolean()) {
                    throw RemoteRpcTransport.protocolIncompatible();
                }
                String name = entry.getKey();
                if (!name.matches("[a-z][a-z0-9-]*")) {
                    throw RemoteRpcTransport.protocolIncompatible();
                }
                if (entry.getValue().booleanValue()) {
                    supported.add(name);
                }
            });
            for (String capability : REQUIRED_CAPABILITIES) {
                if (!supported.contains(capability)) {
                    throw RemoteRpcTransport.protocolIncompatible();
                }
            }
            for (String capability : FORBIDDEN_CAPABILITIES) {
                JsonNode value = capabilityNode.get(capability);
                if (value == null || !value.isBoolean() || value.booleanValue()) {
                    throw RemoteRpcTransport.protocolIncompatible();
                }
            }
            Set<String> unsupported = new LinkedHashSet<>();
            for (JsonNode item : RemoteWireCodec.requiredArray(result, "unsupported")) {
                unsupported.add(RemoteWireCodec.requiredTextValue(item, "unsupported"));
            }
            this.info = new RemoteCodingClientInfo(
                    version, hostName, hostProject, clientId, principalType, supported, unsupported);
            this.projectId = hostProject;
            transport.clientId(clientId);
            return null;
        });
    }

    @Override
    public CodingSessionView create(ProjectId requestedProject, String firstTurn, String idempotencyKey) {
        requireUsable();
        requireProject(requestedProject);
        JsonNode result = transport.call(
                "session/create", RemoteRpcTransport.params("text", firstTurn, "idempotencyKey", idempotencyKey), true);
        CodingSessionView view = decode(() -> codec.sessionView(result));
        remember(view);
        return view;
    }

    @Override
    public CodingSessionView create(
            ProjectId requestedProject, String firstTurn, String idempotencyKey, CodingSessionCreateOptions options) {
        requireUsable();
        requireProject(requestedProject);
        Map<String, Object> params = RemoteRpcTransport.params("text", firstTurn, "idempotencyKey", idempotencyKey);
        Objects.requireNonNull(options, "options must not be null")
                .initialModelId()
                .ifPresent(modelId -> params.put("modelId", modelId));
        JsonNode result = transport.call("session/create", params, true);
        CodingSessionView view = decode(() -> codec.sessionView(result));
        remember(view);
        return view;
    }

    @Override
    public List<CodingSessionSummary> list(ProjectId requestedProject, int limit) {
        requireUsable();
        requireProject(requestedProject);
        return sessions(RemoteRpcTransport.params("limit", limit));
    }

    @Override
    public List<CodingSessionSummary> search(ProjectId requestedProject, String text, int limit) {
        requireUsable();
        requireProject(requestedProject);
        return sessions(RemoteRpcTransport.params("limit", limit, "search", text));
    }

    private List<CodingSessionSummary> sessions(Map<String, Object> params) {
        JsonNode result = transport.call("session/list", params, false);
        return decode(() -> {
            RemoteWireCodec.requireObject(result, "session list");
            JsonNode sessions = result.get("sessions");
            if (sessions == null || !sessions.isArray()) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            RemoteWireCodec.requiredBoolean(result, "truncated");
            RemoteWireCodec.requiredInt(result, "limit");
            List<CodingSessionSummary> values = new ArrayList<>();
            for (JsonNode item : sessions) {
                values.add(codec.summary(item));
            }
            return List.copyOf(values);
        });
    }

    @Override
    public CodingSessionView open(AgentSessionId sessionId) {
        requireUsable();
        String requested = requireSessionId(sessionId);
        CodingSessionView view = decode(() -> codec.sessionView(
                transport.call("session/open", RemoteRpcTransport.params("sessionId", requested), false)));
        requireSessionIdentity(view.summary().sessionId(), sessionId);
        remember(view);
        return view;
    }

    @Override
    public CodingSessionView reconcile(AgentSessionId sessionId) {
        requireUsable();
        String requested = requireSessionId(sessionId);
        ProjectProductException observationFailure = observationFailures.remove(sessionId);
        if (observationFailure != null) {
            throw observationFailure;
        }
        CodingSessionView view = decode(() -> codec.sessionView(
                transport.call("session/reconcile", RemoteRpcTransport.params("sessionId", requested), false)));
        requireSessionIdentity(view.summary().sessionId(), sessionId);
        remember(view);
        return view;
    }

    @Override
    public Optional<AgentRunSnapshot> findRun(AgentRunId runId) {
        requireUsable();
        JsonNode result;
        try {
            result = transport.call("run/get", RemoteRpcTransport.params("runId", requireRunId(runId)), false);
        } catch (ProjectProductException exception) {
            if ("RUN_NOT_FOUND".equals(exception.code())) {
                return Optional.empty();
            }
            throw exception;
        }
        AgentRunSnapshot snapshot = decode(() -> codec.run(result));
        requireRunIdentity(snapshot.runId(), runId);
        rememberRunBinding(result, snapshot.runId());
        return Optional.of(snapshot);
    }

    @Override
    public Optional<CodingRunOutcomeProjection> findOutcome(AgentRunId runId) {
        requireUsable();
        JsonNode result;
        try {
            result = transport.call("run/outcome", RemoteRpcTransport.params("runId", requireRunId(runId)), false);
        } catch (ProjectProductException exception) {
            if ("RUN_NOT_FOUND".equals(exception.code())) {
                return Optional.empty();
            }
            throw exception;
        }
        CodingRunOutcomeProjection outcome = decode(() -> {
            RemoteWireCodec.requireObject(result, "run outcome");
            return new CodingRunOutcomeProjection(
                    new AgentRunId(RemoteWireCodec.requiredText(result, "runId")),
                    enumValue(CodingRunProtocolStatus.class, RemoteWireCodec.requiredText(result, "protocolStatus")),
                    textList(result, "diagnosticCodes"));
        });
        requireRunIdentity(outcome.runId(), runId);
        return Optional.of(outcome);
    }

    @Override
    public CodingSessionHistoryPage history(AgentSessionId sessionId, int limit) {
        requireUsable();
        JsonNode result = transport.call(
                "session/history",
                RemoteRpcTransport.params("sessionId", requireSessionId(sessionId), "limit", limit),
                false);
        CodingSessionHistoryPage page = decode(() -> codec.historyPage(result));
        requireSessionIdentity(page.sessionId(), sessionId);
        return page;
    }

    @Override
    public List<CodingModelOption> models() {
        requireUsable();
        return decode(() -> codec.models(transport.call("model/list", Map.of(), false)));
    }

    @Override
    public CodingModelSelection selectModel(
            AgentSessionId sessionId, String modelId, long expectedRevision, String idempotencyKey) {
        requireUsable();
        JsonNode result = transport.call(
                "session/selectModel",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "modelId",
                        modelId,
                        "expectedRevision",
                        expectedRevision,
                        "idempotencyKey",
                        idempotencyKey),
                true);
        return decode(() -> codec.modelSelection(result));
    }

    @Override
    public void submit(AgentSessionId sessionId, String message, String idempotencyKey) {
        requireUsable();
        JsonNode result = transport.call(
                "run/start",
                RemoteRpcTransport.params(
                        "sessionId", requireSessionId(sessionId), "text", message, "idempotencyKey", idempotencyKey),
                true);
        decode(() -> {
            RemoteWireCodec.requireObject(result, "run start receipt");
            AgentSessionId receiptSession = new AgentSessionId(RemoteWireCodec.requiredText(result, "sessionId"));
            AgentRunId runId = new AgentRunId(RemoteWireCodec.requiredText(result, "runId"));
            requireSessionIdentity(receiptSession, sessionId);
            RemoteWireCodec.requiredText(result, "operation");
            RemoteWireCodec.requiredBoolean(result, "replayed");
            remember(receiptSession, runId);
            return null;
        });
    }

    @Override
    public void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
        requireUsable();
        JsonNode result = transport.call(
                "run/steer",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "runId",
                        requireRunId(activeRunId),
                        "text",
                        message,
                        "idempotencyKey",
                        idempotencyKey),
                true);
        decode(() -> {
            RemoteWireCodec.requireObject(result, "steer receipt");
            AgentRunId receiptRun = new AgentRunId(RemoteWireCodec.requiredText(result, "runId"));
            requireRunIdentity(receiptRun, activeRunId);
            RemoteWireCodec.requiredText(result, "inputId");
            RunInputReceiptStatus receiptStatus =
                    enumValue(RunInputReceiptStatus.class, RemoteWireCodec.requiredText(result, "status"));
            RemoteWireCodec.requiredLong(result, "acceptedAt");
            if (receiptStatus == RunInputReceiptStatus.REJECTED) {
                throw commandRejected("RUN_STEER_REJECTED", result);
            }
            remember(sessionId, receiptRun);
            return null;
        });
    }

    @Override
    public void enqueueFollowUp(
            AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
        requireUsable();
        JsonNode result = transport.call(
                "run/followUp",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "runId",
                        requireRunId(activeRunId),
                        "text",
                        message,
                        "idempotencyKey",
                        idempotencyKey),
                true);
        decode(() -> {
            RemoteWireCodec.requireObject(result, "follow-up receipt");
            AgentSessionId receiptSession = new AgentSessionId(RemoteWireCodec.requiredText(result, "sessionId"));
            requireSessionIdentity(receiptSession, sessionId);
            RemoteWireCodec.requiredText(result, "followUpId");
            enumValue(CodingFollowUpStatus.class, RemoteWireCodec.requiredText(result, "status"));
            RemoteWireCodec.requiredLong(result, "sequence");
            RemoteWireCodec.requiredLong(result, "revision");
            RemoteWireCodec.optionalTextValue(result, "dispatchedRunId")
                    .ifPresent(run -> remember(receiptSession, new AgentRunId(run)));
            return null;
        });
    }

    @Override
    public List<CodingQueuedMessage> restorableMessages(AgentSessionId sessionId, int limit) {
        requireUsable();
        JsonNode result = transport.call(
                "session/queuedMessages",
                RemoteRpcTransport.params("sessionId", requireSessionId(sessionId), "limit", limit),
                false);
        return decode(() -> codec.queuedMessages(result));
    }

    @Override
    public CodingRestoredMessage restore(AgentSessionId sessionId, String followUpId, long revision) {
        requireUsable();
        JsonNode result = transport.call(
                "session/restoreQueued",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "followUpId",
                        followUpId,
                        "expectedRevision",
                        revision),
                true);
        CodingRestoredMessage restored = decode(() -> codec.restoredMessage(result));
        requireSessionIdentity(restored.sessionId(), sessionId);
        return restored;
    }

    @Override
    public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
        requireUsable();
        JsonNode result =
                transport.call("interaction/get", RemoteRpcTransport.params("runId", requireRunId(runId)), false);
        if (result == null || result.isNull()) {
            return Optional.empty();
        }
        InteractionView interaction = decode(() -> codec.interaction(result));
        requireRunIdentity(interaction.runId(), runId);
        remember(interaction.sessionId(), interaction.runId());
        return Optional.of(interaction);
    }

    @Override
    public InteractionResponseReceipt respond(
            InteractionView interaction, InteractionAction action, String idempotencyKey) {
        return respond(interaction, action, List.of(), idempotencyKey);
    }

    @Override
    public InteractionResponseReceipt respond(
            InteractionView interaction, InteractionAction action, List<ContentPart> inputs, String idempotencyKey) {
        requireUsable();
        Objects.requireNonNull(interaction, "interaction must not be null");
        Objects.requireNonNull(action, "action must not be null");
        List<ContentPart> parts = Objects.requireNonNull(inputs, "inputs must not be null");
        Map<String, Object> params = RemoteRpcTransport.params(
                "interactionId",
                interaction.requestId().value(),
                "runId",
                interaction.runId().value(),
                "sessionId",
                interaction.sessionId().value(),
                "revision",
                interaction.revision(),
                "action",
                action.value(),
                "idempotencyKey",
                idempotencyKey);
        if (!parts.isEmpty()) {
            params.put("inputs", encodeInputs(parts));
        }
        JsonNode result = transport.call("interaction/respond", params, true);
        return decode(() -> {
            RemoteWireCodec.requireObject(result, "interaction receipt");
            InteractionResponseReceipt receipt = new InteractionResponseReceipt(
                    new InteractionResponseId(RemoteWireCodec.requiredText(result, "responseId")),
                    new io.haifa.agent.runtime.api.InteractionRequestId(
                            RemoteWireCodec.requiredText(result, "requestId")),
                    new AgentRunId(RemoteWireCodec.requiredText(result, "runId")),
                    enumValue(InteractionResponseReceiptStatus.class, RemoteWireCodec.requiredText(result, "status")),
                    enumValue(InteractionState.class, RemoteWireCodec.requiredText(result, "interactionState")),
                    RemoteWireCodec.requiredLong(result, "revision"),
                    RemoteWireCodec.requiredLong(result, "runVersion"));
            if (!receipt.requestId().equals(interaction.requestId())
                    || !receipt.runId().equals(interaction.runId())) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            remember(interaction.sessionId(), receipt.runId());
            return receipt;
        });
    }

    private List<Map<String, Object>> encodeInputs(List<ContentPart> inputs) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (ContentPart part : inputs) {
            if (!(part instanceof TextPart text)) {
                throw new UnsupportedOperationException("Remote interaction input supports text parts only");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", "text");
            value.put("text", text.text());
            value.put("format", text.format());
            encoded.add(value);
        }
        return encoded;
    }

    @Override
    public Optional<CodingApprovalPrompt> prepareApproval(InteractionView interaction) {
        requireUsable();
        Objects.requireNonNull(interaction, "interaction must not be null");
        JsonNode result = transport.call(
                "approval/prepare",
                RemoteRpcTransport.params("runId", interaction.runId().value()),
                false);
        if (result == null || result.isNull()) {
            return Optional.empty();
        }
        InteractionView pending =
                decode(() -> codec.interaction(RemoteWireCodec.requiredObject(result, "interaction")));
        requireRunIdentity(pending.runId(), interaction.runId());
        remember(pending.sessionId(), pending.runId());
        return Optional.of(new CodingApprovalPrompt(pending, false, Optional.of("REMOTE_SESSION_CONSENT_UNSUPPORTED")));
    }

    @Override
    public InteractionResponseReceipt allowForSession(InteractionView interaction, String idempotencyKey) {
        throw new UnsupportedOperationException("Remote session consent is unavailable");
    }

    @Override
    public void resetSessionApproval(AgentSessionId sessionId) {
        throw new UnsupportedOperationException("Remote session consent is unavailable");
    }

    @Override
    public void cancel(AgentSessionId sessionId, String idempotencyKey) {
        throw new UnsupportedOperationException("Remote cancellation requires an exact Run id");
    }

    @Override
    public void cancel(AgentSessionId sessionId, AgentRunId requestedRunId, String idempotencyKey) {
        requireUsable();
        JsonNode result = transport.call(
                "run/cancel",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "runId",
                        requireRunId(requestedRunId),
                        "idempotencyKey",
                        idempotencyKey),
                true);
        decode(() -> {
            RemoteWireCodec.requireObject(result, "cancel receipt");
            AgentRunId receiptRun = new AgentRunId(RemoteWireCodec.requiredText(result, "runId"));
            requireRunIdentity(receiptRun, requestedRunId);
            RemoteWireCodec.requiredText(result, "commandId");
            RuntimeCommandStatus commandStatus =
                    enumValue(RuntimeCommandStatus.class, RemoteWireCodec.requiredText(result, "status"));
            RemoteWireCodec.requiredLong(result, "runVersion");
            if (commandStatus == RuntimeCommandStatus.REJECTED) {
                throw new ProjectProductException("RUN_CANCEL_REJECTED", "Remote Coding Host rejected the command");
            }
            return null;
        });
    }

    @Override
    public CodingSessionSummary rename(AgentSessionId sessionId, String displayName, long expectedRevision) {
        requireUsable();
        JsonNode result = transport.call(
                "session/rename",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "displayName",
                        displayName,
                        "expectedRevision",
                        expectedRevision),
                true);
        CodingSessionSummary summary = decode(() -> codec.summary(result));
        requireSessionIdentity(summary.sessionId(), sessionId);
        return summary;
    }

    @Override
    public CodingSessionSummary archive(AgentSessionId sessionId, long expectedRevision) {
        requireUsable();
        JsonNode result = transport.call(
                "session/archive",
                RemoteRpcTransport.params(
                        "sessionId", requireSessionId(sessionId), "expectedRevision", expectedRevision),
                true);
        CodingSessionSummary summary = decode(() -> codec.summary(result));
        requireSessionIdentity(summary.sessionId(), sessionId);
        return summary;
    }

    @Override
    public void delete(AgentSessionId sessionId, long expectedRevision) {
        requireUsable();
        JsonNode result = transport.call(
                "session/delete",
                RemoteRpcTransport.params(
                        "sessionId", requireSessionId(sessionId), "expectedRevision", expectedRevision),
                true);
        decode(() -> {
            if (!codec.revoked(result, "deleted")) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            return null;
        });
    }

    @Override
    public CodingCompactionResult compact(AgentSessionId sessionId, String safeInstruction) {
        requireUsable();
        JsonNode result = transport.call(
                "session/compact",
                RemoteRpcTransport.params("sessionId", requireSessionId(sessionId), "instruction", safeInstruction),
                true);
        return decode(() -> codec.compaction(result));
    }

    @Override
    public CodingShellPlan planShell(AgentSessionId sessionId, String command, boolean includeInContext) {
        requireUsable();
        JsonNode result = transport.call(
                "shell/plan",
                RemoteRpcTransport.params(
                        "sessionId",
                        requireSessionId(sessionId),
                        "command",
                        command,
                        "includeInContext",
                        includeInContext),
                true);
        return decode(() -> codec.shellPlan(result));
    }

    @Override
    public CodingShellResult executeShell(String token, boolean approved) {
        requireUsable();
        JsonNode result =
                transport.call("shell/execute", RemoteRpcTransport.params("token", token, "approved", approved), true);
        return decode(() -> codec.shellResult(result));
    }

    @Override
    public void discardShell(String token) {
        requireUsable();
        JsonNode result = transport.call("shell/discard", RemoteRpcTransport.params("token", token), true);
        decode(() -> {
            if (!codec.revoked(result, "discarded")) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            return null;
        });
    }

    @Override
    public CodingSessionExportResult export(AgentSessionId sessionId, String logicalDestination) {
        requireUsable();
        JsonNode result = transport.call(
                "session/export",
                RemoteRpcTransport.params(
                        "sessionId", requireSessionId(sessionId), "logicalDestination", logicalDestination),
                true);
        return decode(() -> codec.exportResult(result));
    }

    @Override
    public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
        requireUsable();
        AgentSessionId sessionId = sessionForRun(runId);
        Map<String, Object> params =
                RemoteRpcTransport.params("sessionId", sessionId.value(), "runId", runId.value(), "limit", limit);
        if (after != null) {
            params.put("afterCursor", cursorParams(after));
        }
        JsonNode result = transport.call("events/page", params, false);
        RunEventPage page = decode(() -> codec.eventPage(result));
        for (AgentRunEvent event : page.items()) {
            if (!sessionId.equals(event.sessionId()) || !runId.equals(event.runId())) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
        }
        requireRunIdentity(page.nextCursor().runId(), runId);
        requireRunIdentity(page.headCursor().runId(), runId);
        page.items().forEach(event -> remember(event.sessionId(), event.runId()));
        return page;
    }

    @Override
    public RunEventCursor acknowledgeCursor(AgentSessionId sessionId, RunEventCursor cursor) {
        requireUsable();
        Objects.requireNonNull(cursor, "cursor must not be null");
        JsonNode result = transport.call(
                "session/ackCursor",
                RemoteRpcTransport.params("sessionId", requireSessionId(sessionId), "cursor", cursorParams(cursor)),
                true);
        RunEventCursor acknowledged = decode(() -> codec.cursor(result, null));
        requireRunIdentity(acknowledged.runId(), cursor.runId());
        remember(sessionId, acknowledged.runId());
        return acknowledged;
    }

    @Override
    public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        requireUsable();
        Objects.requireNonNull(listener, "listener must not be null");
        AgentSessionId sessionId = sessionForRun(runId);
        Map<String, Object> params = RemoteRpcTransport.params("sessionId", sessionId.value(), "runId", runId.value());
        if (after != null) {
            params.put("afterCursor", cursorParams(after));
        }
        JsonNode acknowledgement = transport.call("events/subscribe", params, false);
        String subscriptionId = decode(() -> subscriptionId(acknowledgement, sessionId, runId, "run-event"));
        RemoteSseSubscription subscription = new RemoteSseSubscription(
                transport,
                executor,
                subscriptionId,
                sessionId,
                runId,
                "run-event",
                node -> onEventNode(sessionId, runId, listener, node),
                failure -> recordObservationFailure(sessionId, failure),
                this::retire);
        install(subscription);
        return subscription;
    }

    private void onEventNode(
            AgentSessionId sessionId, AgentRunId runId, AgentRunEventListener listener, JsonNode node) {
        AgentRunEvent event = decode(() -> codec.event(node));
        if (!sessionId.equals(event.sessionId()) || !runId.equals(event.runId())) {
            throw new ProjectProductException(
                    "REMOTE_EVENT_TARGET_MISMATCH", "Remote event does not match the subscribed Session and Run");
        }
        remember(event.sessionId(), event.runId());
        try {
            listener.onEvent(event);
        } catch (RuntimeException ignored) {
            // Listener failures never escape into the transport observation.
        }
    }

    @Override
    public RunOutputSubscription subscribeOutput(
            AgentRunId runId, RunOutputCursor after, AgentRunOutputListener listener) {
        requireUsable();
        Objects.requireNonNull(listener, "listener must not be null");
        AgentSessionId sessionId = sessionForRun(runId);
        Map<String, Object> params = RemoteRpcTransport.params("sessionId", sessionId.value(), "runId", runId.value());
        if (after != null) {
            params.put("afterCursor", after.sequence());
        }
        JsonNode acknowledgement = transport.call("output/subscribe", params, false);
        String subscriptionId = decode(() -> subscriptionId(acknowledgement, sessionId, runId, "output"));
        RemoteSseSubscription subscription = new RemoteSseSubscription(
                transport,
                executor,
                subscriptionId,
                sessionId,
                runId,
                "output",
                node -> onOutputNode(sessionId, runId, listener, node),
                failure -> recordObservationFailure(sessionId, failure),
                this::retire);
        install(subscription);
        return subscription;
    }

    private void install(RemoteSseSubscription subscription) {
        synchronized (lifecycleLock) {
            if (closed) {
                subscription.close();
                throw RemoteRpcTransport.unavailable();
            }
            subscriptions.add(subscription);
            try {
                subscription.start();
            } catch (RuntimeException exception) {
                subscription.close();
                throw RemoteRpcTransport.unavailable();
            }
        }
    }

    private void onOutputNode(
            AgentSessionId sessionId, AgentRunId runId, AgentRunOutputListener listener, JsonNode node) {
        AgentRunOutputEvent event = decode(() -> codec.outputEvent(node));
        String wireSession = decode(() -> codec.outputSessionId(node));
        if (!sessionId.value().equals(wireSession) || !runId.equals(event.runId())) {
            throw new ProjectProductException(
                    "REMOTE_EVENT_TARGET_MISMATCH", "Remote output does not match the subscribed Session and Run");
        }
        remember(sessionId, event.runId());
        try {
            listener.onOutput(event);
        } catch (RuntimeException ignored) {
            // Listener failures never escape into the transport observation.
        }
    }

    private String subscriptionId(JsonNode acknowledgement, AgentSessionId sessionId, AgentRunId runId, String type) {
        RemoteWireCodec.requireObject(acknowledgement, "subscription acknowledgement");
        String subscriptionId = RemoteWireCodec.requiredText(acknowledgement, "subscriptionId");
        String acknowledgedSession = RemoteWireCodec.requiredText(acknowledgement, "sessionId");
        String acknowledgedRun = RemoteWireCodec.requiredText(acknowledgement, "runId");
        String acknowledgedType = RemoteWireCodec.requiredText(acknowledgement, "type");
        if (!sessionId.value().equals(acknowledgedSession)
                || !runId.value().equals(acknowledgedRun)
                || !type.equals(acknowledgedType)) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        return subscriptionId;
    }

    @Override
    public List<String> logicalPaths() {
        requireUsable();
        return decode(() -> textList(transport.call("workspace/paths", Map.of(), false), "paths"));
    }

    @Override
    public List<CodingWorkspaceView> workspaces() {
        requireUsable();
        return decode(() -> codec.workspaces(transport.call("workspace/list", Map.of(), false)));
    }

    @Override
    public void revokeWorkspace(String workspaceRef) {
        requireUsable();
        JsonNode result =
                transport.call("workspace/revoke", RemoteRpcTransport.params("workspaceRef", workspaceRef), true);
        decode(() -> {
            if (!codec.revoked(result, "revoked")) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            return null;
        });
    }

    @Override
    public List<String> loadedResources() {
        requireUsable();
        return decode(() -> textList(transport.call("resources/loaded", Map.of(), false), "resources"));
    }

    @Override
    public List<String> reloadResources() {
        requireUsable();
        return decode(() -> textList(transport.call("resources/reload", Map.of(), false), "resources"));
    }

    @Override
    public void close() {
        List<RemoteSseSubscription> owned;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            owned = List.copyOf(subscriptions);
            subscriptions.clear();
        }
        for (RemoteSseSubscription subscription : owned) {
            subscription.close();
        }
        transport.close();
        executor.shutdownNow();
    }

    private void requireUsable() {
        if (closed) {
            throw RemoteRpcTransport.unavailable();
        }
    }

    private AgentSessionId sessionForRun(AgentRunId runId) {
        AgentSessionId cached = runSessions.get(Objects.requireNonNull(runId, "runId must not be null"));
        if (cached != null) {
            return cached;
        }
        return resolveSessionForRun(runId);
    }

    /**
     * Resolves a historical Run to its Session through the additive {@code run/get.sessionId} binding. The
     * adapter never guesses the currently selected Session and never drops a binding when another Session opens.
     */
    private AgentSessionId resolveSessionForRun(AgentRunId runId) {
        JsonNode result = transport.call("run/get", RemoteRpcTransport.params("runId", runId.value()), false);
        AgentSessionId resolved = decode(() -> {
            RemoteWireCodec.requireObject(result, "run snapshot");
            AgentRunId wireRunId = new AgentRunId(RemoteWireCodec.requiredText(result, "runId"));
            if (!runId.equals(wireRunId)) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            return new AgentSessionId(RemoteWireCodec.requiredText(result, "sessionId"));
        });
        runSessions.put(runId, resolved);
        return resolved;
    }

    private void rememberRunBinding(JsonNode runResult, AgentRunId runId) {
        AgentSessionId sessionId =
                decode(() -> new AgentSessionId(RemoteWireCodec.requiredText(runResult, "sessionId")));
        remember(sessionId, runId);
    }

    private void remember(CodingSessionView view) {
        if (view.activeRun().isPresent()) {
            remember(view.summary().sessionId(), view.activeRun().orElseThrow().runId());
        }
        view.pendingInteraction().ifPresent(interaction -> remember(interaction.sessionId(), interaction.runId()));
    }

    private void remember(AgentSessionId sessionId, AgentRunId runId) {
        if (sessionId != null && runId != null) {
            runSessions.put(runId, sessionId);
        }
    }

    private void recordObservationFailure(AgentSessionId sessionId, ProjectProductException failure) {
        if (isRecoverableObservationFailure(failure)) {
            return;
        }
        observationFailures.putIfAbsent(sessionId, failure);
    }

    private static boolean isRecoverableObservationFailure(ProjectProductException failure) {
        return "REMOTE_STREAM_CLOSED".equals(failure.code()) || "REMOTE_TRANSPORT_UNAVAILABLE".equals(failure.code());
    }

    private void retire(RemoteSseSubscription subscription) {
        subscriptions.remove(subscription);
        if (closed) {
            return;
        }
        String method = "output".equals(subscription.type()) ? "output/unsubscribe" : "events/unsubscribe";
        transport.callBestEffort(method, RemoteRpcTransport.params("subscriptionId", subscription.subscriptionId()));
    }

    private static void requireSessionIdentity(AgentSessionId actual, AgentSessionId expected) {
        if (!expected.equals(actual)) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }

    private static void requireRunIdentity(AgentRunId actual, AgentRunId expected) {
        if (!expected.equals(actual)) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }

    private static ProjectProductException commandRejected(String fallbackCode, JsonNode result) {
        String reason = RemoteWireCodec.optionalTextValue(result, "reasonCode").orElse(fallbackCode);
        if (!reason.matches("[A-Z][A-Z0-9_.:-]{0,95}")) {
            reason = fallbackCode;
        }
        return new ProjectProductException(reason, "Remote Coding Host rejected the command");
    }

    private void requireProject(ProjectId requested) {
        if (!Objects.equals(projectId, requested)) {
            throw new ProjectProductException("SESSION_PROJECT_MISMATCH", "Project is unavailable");
        }
    }

    private static String requireSessionId(AgentSessionId sessionId) {
        return Objects.requireNonNull(sessionId, "sessionId must not be null").value();
    }

    private static String requireRunId(AgentRunId runId) {
        return Objects.requireNonNull(runId, "runId must not be null").value();
    }

    private Map<String, Object> cursorParams(RunEventCursor cursor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runId", cursor.runId().value());
        value.put("feedVersion", cursor.feedVersion());
        value.put(
                "sequence",
                cursor.exclusiveSequence().isPresent()
                        ? cursor.exclusiveSequence().getAsLong()
                        : null);
        return value;
    }

    private List<String> textList(JsonNode node, String field) {
        return decode(() -> codec.textList(node, field));
    }

    private static <T> T decode(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ProjectProductException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
    }

    private static String requireClientText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
