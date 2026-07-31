package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.ProjectProductSession;
import io.haifa.agent.application.project.product.ProjectProductSessionStore;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.application.project.product.TrustedProductCallerProvider;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Coding product façade. Product queue/read-model state is kept separate from the authoritative
 * Core Session and Runtime Run lifecycle.
 */
public final class CodingSessionService {
    private static final String CREATE = "create-session";
    private static final String SUBMIT = "submit-turn";
    private static final String STEER = "steer";
    private static final String ABORT = "abort";

    private final ProjectProductService projectProducts;
    private final ProjectProductSessionStore productSessions;
    private final CodingSessionStore codingSessions;
    private final CodingSessionLifecycle sessionLifecycle;
    private final CodingSessionCompactor sessionCompactor;
    private final TrustedProductCallerProvider callers;
    private final AgentRuntime runtime;
    private final IdentifierGenerator identifiers;
    private final Clock clock;

    public CodingSessionService(
            ProjectProductService projectProducts,
            ProjectProductSessionStore productSessions,
            CodingSessionStore codingSessions,
            CodingSessionLifecycle sessionLifecycle,
            CodingSessionCompactor sessionCompactor,
            TrustedProductCallerProvider callers,
            AgentRuntime runtime,
            IdentifierGenerator identifiers,
            Clock clock) {
        this.projectProducts = Objects.requireNonNull(projectProducts, "projectProducts must not be null");
        this.productSessions = Objects.requireNonNull(productSessions, "productSessions must not be null");
        this.codingSessions = Objects.requireNonNull(codingSessions, "codingSessions must not be null");
        this.sessionLifecycle = Objects.requireNonNull(sessionLifecycle, "sessionLifecycle must not be null");
        this.sessionCompactor = Objects.requireNonNull(sessionCompactor, "sessionCompactor must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CodingSessionView createSession(
            ProjectId projectId, String firstTurn, List<AssetRef> attachments, String idempotencyKey) {
        TrustedProductCaller caller = callers.current();
        Instant now = now();
        List<AssetRef> safeAttachments = attachments(attachments);
        String message = message(firstTurn);
        String keyDigest = digest(idempotencyKey(idempotencyKey));
        String requestDigest = requestDigest(projectId.value(), message, safeAttachments);
        String scope = callerScope(caller);
        String dispatchKey = dispatchKey(CREATE, scope + "|" + keyDigest);
        CodingCommandBinding binding = codingSessions.reserveCommand(new CodingCommandBinding(
                scope,
                CREATE,
                keyDigest,
                requestDigest,
                dispatchKey,
                new AgentSessionId(identifiers.nextValue()),
                projectId,
                message,
                safeAttachments,
                Optional.empty(),
                now));

        ProjectProductService.ProjectProductRun started = projectProducts.startWithSessionId(
                binding.projectId(),
                binding.sessionId(),
                binding.message(),
                binding.attachments(),
                binding.dispatchKey());
        codingSessions.completeCommand(binding.dispatchKey(), started.run().runId());
        ProjectProductSession product = requireProductSession(binding.sessionId(), caller);
        CodingSessionActivity activity = codingSessions
                .findActivity(binding.sessionId())
                .orElseGet(() -> codingSessions.createActivity(new CodingSessionActivity(
                        binding.sessionId(),
                        product.projectId(),
                        product.tenant(),
                        product.principal(),
                        displayName(binding.message()),
                        AgentSessionStatus.ACTIVE,
                        Optional.of(started.run().runId()),
                        OptionalLong.of(started.run().version()),
                        Optional.empty(),
                        now,
                        now,
                        0)));
        if (activity.activeRunId().isEmpty()) {
            activity =
                    codingSessions.reserveActive(activity.sessionId(), activity.revision(), binding.dispatchKey(), now);
            activity = codingSessions.activateRun(
                    activity.sessionId(),
                    binding.dispatchKey(),
                    started.run().runId(),
                    started.run().version(),
                    now);
        }
        return view(activity, product);
    }

    public CodingSessionPage listSessions(ProjectId projectId, CodingSessionQuery query) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(query, "query must not be null");
        TrustedProductCaller caller = callers.current();
        List<CodingSessionActivity> values =
                codingSessions.listActivities(caller.tenant(), caller.principal(), projectId, query);
        boolean hasMore = values.size() > query.limit();
        List<CodingSessionActivity> pageValues = hasMore ? values.subList(0, query.limit()) : values;
        List<CodingSessionSummary> summaries = pageValues.stream()
                .map(value -> {
                    CodingSessionActivity reconciled = reconcile(value, caller);
                    return summary(reconciled);
                })
                .toList();
        Optional<CodingSessionCursor> next = hasMore && !pageValues.isEmpty()
                ? Optional.of(new CodingSessionCursor(
                        pageValues.getLast().lastActivityAt(),
                        pageValues.getLast().sessionId()))
                : Optional.empty();
        return new CodingSessionPage(summaries, next, hasMore);
    }

    public CodingSessionView openSession(AgentSessionId sessionId) {
        TrustedProductCaller caller = callers.current();
        ProjectProductSession product = requireProductSession(sessionId, caller);
        CodingSessionActivity activity = requireActivity(sessionId, caller);
        return view(reconcile(activity, caller), product);
    }

    public CodingSessionSummary renameSession(AgentSessionId sessionId, String displayName, long expectedRevision) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        CodingSessionActivity current = requireActivity(sessionId, caller);
        if (current.revision() != expectedRevision) {
            throw conflict("SESSION_REVISION_STALE", "Coding Session revision is stale");
        }
        return summary(codingSessions.rename(sessionId, expectedRevision, renameDisplayName(displayName), now()));
    }

    public CodingSessionSummary archiveSession(AgentSessionId sessionId, long expectedRevision) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        CodingSessionActivity current = requireActivity(sessionId, caller);
        requireLifecycleChange(current, expectedRevision);
        return summary(sessionLifecycle.archive(sessionId, expectedRevision, now()));
    }

    public void deleteSession(AgentSessionId sessionId, long expectedRevision) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        CodingSessionActivity current = requireActivity(sessionId, caller);
        requireLifecycleChange(current, expectedRevision);
        sessionLifecycle.delete(sessionId, expectedRevision, now());
    }

    public CodingCompactionResult compactSession(AgentSessionId sessionId, String safeInstruction) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        CodingSessionActivity current = reconcile(requireActivity(sessionId, caller), caller);
        requireActiveSession(current);
        if (current.activeRunId().isPresent() || current.activeDispatchKey().isPresent()) {
            throw conflict("CODING_SESSION_ACTIVE", "Active Coding Session cannot be compacted");
        }
        if (safeInstruction != null && !safeInstruction.isBlank()) {
            throw new UnsupportedOperationException("COMPACTION_INSTRUCTION_NOT_SUPPORTED");
        }
        return sessionCompactor.compact(sessionId);
    }

    public CodingSessionCommandReceipt submitTurn(
            AgentSessionId sessionId, String message, List<AssetRef> attachments, String idempotencyKey) {
        TrustedProductCaller caller = callers.current();
        ProjectProductSession product = requireProductSession(sessionId, caller);
        CodingSessionActivity activity = reconcile(requireActivity(sessionId, caller), caller);
        requireActiveSession(activity);
        String safeMessage = message(message);
        List<AssetRef> safeAttachments = attachments(attachments);
        String keyDigest = digest(idempotencyKey(idempotencyKey));
        String requestDigest = requestDigest(sessionId.value(), safeMessage, safeAttachments);
        String dispatchKey = dispatchKey(SUBMIT, callerScope(caller) + "|" + keyDigest);
        CodingCommandBinding existing = codingSessions.reserveCommand(new CodingCommandBinding(
                callerScope(caller),
                SUBMIT,
                keyDigest,
                requestDigest,
                dispatchKey,
                sessionId,
                product.projectId(),
                safeMessage,
                safeAttachments,
                Optional.empty(),
                now()));
        if (existing.runId().isPresent()) {
            return new CodingSessionCommandReceipt(
                    SUBMIT, sessionId, existing.runId().orElseThrow(), true);
        }
        if (activity.activeRunId().isPresent()) {
            throw conflict("CODING_SESSION_ACTIVE", "Coding Session already has an active Run");
        }
        codingSessions.reserveActive(sessionId, activity.revision(), existing.dispatchKey(), now());
        var started = projectProducts.continueSession(
                sessionId, existing.message(), existing.attachments(), existing.dispatchKey());
        codingSessions.completeCommand(existing.dispatchKey(), started.run().runId());
        codingSessions.activateRun(
                sessionId,
                existing.dispatchKey(),
                started.run().runId(),
                started.run().version(),
                now());
        return new CodingSessionCommandReceipt(SUBMIT, sessionId, started.run().runId(), false);
    }

    public RunInputReceipt steer(
            AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
        TrustedProductCaller caller = callers.current();
        ProjectProductSession product = requireProductSession(sessionId, caller);
        CodingSessionActivity activity = reconcile(requireActivity(sessionId, caller), caller);
        requireActiveSession(activity);
        AgentRunSnapshot active = requireActive(activity, activeRunId);
        String safeMessage = message(message);
        String keyDigest = digest(idempotencyKey(idempotencyKey));
        String requestDigest = requestDigest(activeRunId.value(), safeMessage, List.of());
        String dispatchKey = dispatchKey(STEER, callerScope(caller) + "|" + keyDigest);
        CodingCommandBinding binding = codingSessions.reserveCommand(new CodingCommandBinding(
                callerScope(caller),
                STEER,
                keyDigest,
                requestDigest,
                dispatchKey,
                sessionId,
                product.projectId(),
                safeMessage,
                List.of(),
                Optional.empty(),
                now()));
        RunInputReceipt receipt = runtime.submitInput(new RunInputSubmission(
                new RunInputId(binding.dispatchKey()),
                active.runId(),
                OptionalLong.of(active.version()),
                List.of(new TextPart(binding.message(), "text/plain")),
                binding.dispatchKey(),
                now()));
        codingSessions.completeCommand(binding.dispatchKey(), active.runId());
        return receipt;
    }

    public CodingFollowUpReceipt enqueueFollowUp(
            AgentSessionId sessionId,
            AgentRunId activeRunId,
            String message,
            List<AssetRef> attachments,
            String idempotencyKey) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        CodingSessionActivity activity = reconcile(requireActivity(sessionId, caller), caller);
        requireActiveSession(activity);
        requireActive(activity, activeRunId);
        String safeMessage = message(message);
        List<AssetRef> safeAttachments = attachments(attachments);
        String keyDigest = digest(idempotencyKey(idempotencyKey));
        String followUpId = identifiers.nextValue();
        Instant now = now();
        CodingFollowUp stored = codingSessions.enqueue(new CodingFollowUp(
                followUpId,
                sessionId,
                activeRunId,
                safeMessage,
                safeAttachments,
                keyDigest,
                requestDigest(activeRunId.value(), safeMessage, safeAttachments),
                dispatchKey("follow-up", followUpId),
                CodingFollowUpStatus.PENDING,
                1,
                Optional.empty(),
                now,
                now,
                0));
        return CodingFollowUpReceipt.from(stored);
    }

    public CodingRestoredMessage restoreQueuedMessage(
            AgentSessionId sessionId, String followUpId, long expectedRevision) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        requireActiveSession(requireActivity(sessionId, caller));
        CodingFollowUp existing = codingSessions
                .findFollowUp(followUpId)
                .filter(value -> value.sessionId().equals(sessionId))
                .orElseThrow(() -> conflict("FOLLOW_UP_NOT_FOUND", "Follow-up is unavailable"));
        CodingFollowUp restored = codingSessions.restore(existing.followUpId(), expectedRevision, now());
        return new CodingRestoredMessage(
                restored.followUpId(),
                restored.sessionId(),
                restored.message(),
                restored.attachments(),
                restored.revision());
    }

    public List<CodingQueuedMessage> listRestorableMessages(AgentSessionId sessionId, int limit) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        requireActivity(sessionId, caller);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return codingSessions.listRestorableFollowUps(sessionId, limit).stream()
                .map(value -> new CodingQueuedMessage(
                        value.followUpId(),
                        value.sessionId(),
                        displayName(value.message()),
                        value.sequence(),
                        value.revision()))
                .toList();
    }

    public RunEventCursor acknowledgeEventCursor(AgentSessionId sessionId, RunEventCursor cursor) {
        TrustedProductCaller caller = callers.current();
        requireProductSession(sessionId, caller);
        CodingSessionActivity activity = requireActivity(sessionId, caller);
        if (activity.activeRunId().isPresent()
                && activity.activeRunId().filter(cursor.runId()::equals).isEmpty()) {
            throw conflict("EVENT_CURSOR_RUN_MISMATCH", "Event cursor is unavailable");
        }
        if (runtime.view(cursor.runId())
                .filter(view -> view.sessionId().equals(sessionId))
                .isEmpty()) {
            throw conflict("EVENT_CURSOR_RUN_MISMATCH", "Event cursor is unavailable");
        }
        return codingSessions.saveEventCursor(sessionId, cursor, now());
    }

    public RuntimeCommandResult abortActiveRun(AgentSessionId sessionId, String idempotencyKey) {
        TrustedProductCaller caller = callers.current();
        ProjectProductSession product = requireProductSession(sessionId, caller);
        CodingSessionActivity activity = reconcile(requireActivity(sessionId, caller), caller);
        AgentRunSnapshot active = requireActive(activity, activity.activeRunId().orElseThrow());
        String keyDigest = digest(idempotencyKey(idempotencyKey));
        String dispatchKey = dispatchKey(ABORT, callerScope(caller) + "|" + keyDigest);
        CodingCommandBinding binding = codingSessions.reserveCommand(new CodingCommandBinding(
                callerScope(caller),
                ABORT,
                keyDigest,
                requestDigest(active.runId().value(), ABORT, List.of()),
                dispatchKey,
                sessionId,
                product.projectId(),
                ABORT,
                List.of(),
                Optional.empty(),
                now()));
        RuntimeCommandResult result = runtime.command(new RuntimeCommand(
                new RuntimeCommandId(binding.dispatchKey()),
                active.runId(),
                RuntimeCommandType.CANCEL,
                RuntimeCommandArguments.NONE,
                OptionalLong.of(active.version()),
                binding.dispatchKey(),
                now()));
        codingSessions.completeCommand(binding.dispatchKey(), active.runId());
        return result;
    }

    public CodingSessionView reconcileSession(AgentSessionId sessionId) {
        TrustedProductCaller caller = callers.current();
        ProjectProductSession product = requireProductSession(sessionId, caller);
        return view(reconcile(requireActivity(sessionId, caller), caller), product);
    }

    private CodingSessionActivity reconcile(CodingSessionActivity activity, TrustedProductCaller caller) {
        requireOwned(activity, caller);
        CodingSessionActivity current = activity;
        if (current.activeRunId().isPresent()) {
            AgentRunId runId = current.activeRunId().orElseThrow();
            AgentRunSnapshot snapshot = runtime.find(runId)
                    .orElseThrow(() -> conflict("ACTIVE_RUN_UNAVAILABLE", "Active Run is unavailable"));
            if (!snapshot.status().isTerminal()) return current;
            current = codingSessions.clearActive(current.sessionId(), runId, current.revision(), now());
        }
        if (current.activeDispatchKey().isPresent()) {
            return recoverReservedDispatch(current);
        }
        if (current.status() != AgentSessionStatus.ACTIVE) return current;
        Optional<CodingDispatchClaim> claimed =
                codingSessions.claimNextForDispatch(current.sessionId(), current.revision(), now());
        if (claimed.isEmpty()) return current;
        return dispatchFollowUp(claimed.orElseThrow());
    }

    private CodingSessionActivity recoverReservedDispatch(CodingSessionActivity activity) {
        String dispatchKey = activity.activeDispatchKey().orElseThrow();
        Optional<CodingCommandBinding> command = codingSessions.findCommandByDispatchKey(dispatchKey);
        if (command.isPresent()) {
            CodingCommandBinding value = command.orElseThrow();
            AgentRunSnapshot run = value.runId().flatMap(runtime::find).orElseGet(() -> projectProducts
                    .continueSession(activity.sessionId(), value.message(), value.attachments(), value.dispatchKey())
                    .run());
            codingSessions.completeCommand(value.dispatchKey(), run.runId());
            return codingSessions.activateRun(activity.sessionId(), dispatchKey, run.runId(), run.version(), now());
        }
        CodingFollowUp followUp = codingSessions
                .findFollowUpByDispatchKey(dispatchKey)
                .orElseThrow(() -> conflict("DISPATCH_FACT_MISSING", "Reserved dispatch has no durable request"));
        return dispatchFollowUp(new CodingDispatchClaim(activity, followUp));
    }

    private CodingSessionActivity dispatchFollowUp(CodingDispatchClaim claim) {
        CodingFollowUp claimed = claim.followUp();
        AgentRunSnapshot run = claimed.dispatchedRunId().flatMap(runtime::find).orElseGet(() -> projectProducts
                .continueSession(claimed.sessionId(), claimed.message(), claimed.attachments(), claimed.dispatchKey())
                .run());
        CodingFollowUp followUp = claimed.status() == CodingFollowUpStatus.DISPATCHED
                ? claimed
                : codingSessions.markDispatched(claimed.followUpId(), claimed.revision(), run.runId(), now());
        return codingSessions.activateRun(
                followUp.sessionId(), followUp.dispatchKey(), run.runId(), run.version(), now());
    }

    private CodingSessionView view(CodingSessionActivity activity, ProjectProductSession product) {
        CodingSessionSummary summary = summary(activity);
        Optional<AgentRunSnapshot> active = activity.activeRunId().flatMap(runtime::find);
        Optional<io.haifa.agent.runtime.api.InteractionView> interaction =
                active.flatMap(value -> pendingInteraction(value.runId()));
        Optional<RunEventCursor> cursor = active.flatMap(
                value -> codingSessions.findEventCursor(activity.sessionId()).filter(stored -> stored.runId()
                        .equals(value.runId())));
        return new CodingSessionView(
                summary, active, interaction, cursor, product.configurationDigest(), product.productProfileRef());
    }

    private CodingSessionSummary summary(CodingSessionActivity activity) {
        Optional<AgentRunSnapshot> active = activity.activeRunId().flatMap(runtime::find);
        return new CodingSessionSummary(
                activity.sessionId(),
                activity.projectId(),
                activity.displayName(),
                activity.status(),
                active.map(AgentRunSnapshot::runId),
                active.map(AgentRunSnapshot::status),
                codingSessions.queuedCount(activity.sessionId()),
                activity.lastActivityAt(),
                activity.revision());
    }

    private Optional<io.haifa.agent.runtime.api.InteractionView> pendingInteraction(AgentRunId runId) {
        try {
            return runtime.pendingInteraction(runId);
        } catch (UnsupportedOperationException ignored) {
            return Optional.empty();
        }
    }

    private ProjectProductSession requireProductSession(AgentSessionId sessionId, TrustedProductCaller caller) {
        ProjectProductSession session = productSessions
                .find(sessionId)
                .orElseThrow(() -> conflict("SESSION_NOT_FOUND", "Session is unavailable"));
        if (!session.tenant().equals(caller.tenant()) || !session.principal().equals(caller.principal())) {
            throw conflict("SESSION_NOT_FOUND", "Session is unavailable");
        }
        return session;
    }

    private CodingSessionActivity requireActivity(AgentSessionId sessionId, TrustedProductCaller caller) {
        CodingSessionActivity activity = codingSessions
                .findActivity(sessionId)
                .orElseThrow(() -> conflict("SESSION_NOT_FOUND", "Session is unavailable"));
        requireOwned(activity, caller);
        if (activity.status() == AgentSessionStatus.DELETED) {
            throw conflict("SESSION_NOT_FOUND", "Session is unavailable");
        }
        return activity;
    }

    private static void requireOwned(CodingSessionActivity activity, TrustedProductCaller caller) {
        if (!activity.tenant().equals(caller.tenant()) || !activity.principal().equals(caller.principal())) {
            throw conflict("SESSION_NOT_FOUND", "Session is unavailable");
        }
    }

    private static void requireActiveSession(CodingSessionActivity activity) {
        if (activity.status() != AgentSessionStatus.ACTIVE) {
            throw conflict("SESSION_NOT_ACTIVE", "Coding Session is not active");
        }
    }

    private static void requireLifecycleChange(CodingSessionActivity activity, long expectedRevision) {
        if (activity.revision() != expectedRevision) {
            throw conflict("SESSION_REVISION_STALE", "Coding Session revision is stale");
        }
        if (activity.activeRunId().isPresent() || activity.activeDispatchKey().isPresent()) {
            throw conflict("CODING_SESSION_ACTIVE", "Active Coding Session cannot change lifecycle status");
        }
    }

    private AgentRunSnapshot requireActive(CodingSessionActivity activity, AgentRunId requestedRunId) {
        if (activity.activeRunId().filter(requestedRunId::equals).isEmpty()) {
            throw conflict("ACTIVE_RUN_MISMATCH", "Requested Run is not the active Run");
        }
        AgentRunSnapshot snapshot = runtime.find(requestedRunId)
                .orElseThrow(() -> conflict("ACTIVE_RUN_UNAVAILABLE", "Active Run is unavailable"));
        if (snapshot.status().isTerminal()) {
            throw conflict("ACTIVE_RUN_SETTLED", "Active Run is already settled");
        }
        return snapshot;
    }

    private Instant now() {
        return TimePrecision.now(clock);
    }

    private static List<AssetRef> attachments(List<AssetRef> values) {
        List<AssetRef> copy = List.copyOf(Objects.requireNonNull(values, "attachments must not be null"));
        if (copy.size() > 20 || copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("attachments must contain at most 20 values");
        }
        return copy;
    }

    private static String displayName(String message) {
        String firstLine = message.lines().findFirst().orElse("Coding Session");
        StringBuilder safe = new StringBuilder();
        firstLine
                .codePoints()
                .filter(value -> !Character.isISOControl(value))
                .limit(80)
                .forEach(safe::appendCodePoint);
        String result = safe.toString().trim();
        return result.isEmpty() ? "Coding Session" : result;
    }

    private static String renameDisplayName(String value) {
        if (value == null || value.isBlank()) return "Coding Session";
        return CodingProductValues.requireText(value.trim(), "displayName", 120);
    }

    private static String message(String value) {
        return CodingProductValues.requireText(value, "message", 65_536);
    }

    private static String idempotencyKey(String value) {
        return CodingProductValues.requireText(value, "idempotencyKey", 256);
    }

    private static String callerScope(TrustedProductCaller caller) {
        return digest(caller.tenant().tenantId() + "\0" + caller.principal().principalType() + "\0"
                + caller.principal().principalId());
    }

    private static String requestDigest(String target, String message, List<AssetRef> attachments) {
        StringBuilder value = new StringBuilder();
        append(value, target);
        append(value, message);
        attachments.forEach(asset -> {
            append(value, asset.assetId());
            append(value, asset.mimeType());
            append(value, asset.filename());
        });
        return digest(value.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String dispatchKey(String operation, String value) {
        int digestStart = "sha256:".length();
        return "coding-" + operation + "-" + digest(value).substring(digestStart, digestStart + 32);
    }

    private static String digest(String value) {
        try {
            byte[] result = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ProjectProductException conflict(String code, String message) {
        return new ProjectProductException(code, message);
    }
}
