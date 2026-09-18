package io.haifa.agent.sdk.internal;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.storage.AppliedCommandResult;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.conversation.ChangeConversationStatusCommand;
import io.haifa.agent.sdk.conversation.ConversationCursor;
import io.haifa.agent.sdk.conversation.ConversationPage;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationRun;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.conversation.ConversationTurn;
import io.haifa.agent.sdk.conversation.ConversationTurnCursor;
import io.haifa.agent.sdk.conversation.ConversationTurnPage;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultConversationService implements ConversationService {
    private static final int MESSAGE_PAGE_SIZE = 200;

    private final ProductProfile profile;
    private final AgentRuntime runtime;
    private final SdkPersistenceContribution persistence;
    private final ConversationStore conversations;
    private final SdkCallerProvider callers;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public DefaultConversationService(
            ProductProfile profile,
            AgentRuntime runtime,
            SdkPersistenceContribution persistence,
            ConversationStore conversations,
            SdkCallerProvider callers,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public ConversationRun start(StartConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        String dispatchKey = dispatchKey("start", callerScope(caller), keyDigest(command.idempotencyKey()));
        Instant now = time.now();
        return persistence.inTransaction(() -> {
            Optional<AgentRunId> recovered = idempotency().findRun(runtimeScope(caller), "start", dispatchKey);
            AgentSessionId sessionId;
            AgentSession session;
            if (recovered.isPresent()) {
                sessionId = resolveSessionId(recovered.orElseThrow(), caller);
                session = requireAuthorizedSession(sessionId, caller);
                if (conversations.find(sessionId).isEmpty()) {
                    conversations.create(
                            metadata(sessionId, command.displayName(), now), caller.tenant(), caller.principal());
                }
            } else {
                sessionId = new AgentSessionId(ids.nextValue());
                session = AgentSession.open(
                        sessionId,
                        caller.tenant(),
                        caller.principal(),
                        null,
                        SessionScope.USER,
                        now,
                        Map.of("productId", profile.productId().value()));
                sessions().insert(session);
                conversations.create(
                        metadata(sessionId, command.displayName(), now), caller.tenant(), caller.principal());
            }
            AgentRunSnapshot run = runtime.start(runRequest(
                    dispatchKey,
                    sessionId,
                    command.message(),
                    command.runProfileId(),
                    command.inputs(),
                    command.structuredOutput()));
            if (recovered.isPresent()) {
                ConversationRecord record =
                        conversations.find(sessionId).orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
                return new ConversationRun(withStatus(record, session), run.runId(), run.version());
            }
            ConversationRecord touched = conversations.touchLastActivity(sessionId, time.now());
            return new ConversationRun(withStatus(touched, session), run.runId(), run.version());
        });
    }

    @Override
    public Optional<ConversationRecord> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        SdkCaller caller = caller();
        return conversations.find(sessionId).flatMap(metadata -> visible(metadata, caller));
    }

    @Override
    public ConversationPage list(ConversationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        SdkCaller caller = caller();
        List<ConversationRecord> matched = new ArrayList<>();
        Optional<ConversationCursor> cursor = query.after();
        boolean exhausted = false;
        while (matched.size() <= query.limit() && !exhausted) {
            List<ConversationRecord> page = conversations.list(
                    caller.tenant(),
                    caller.principal(),
                    new ConversationQuery(query.text(), query.statuses(), cursor, query.limit()));
            for (ConversationRecord metadata : page) {
                visible(metadata, caller)
                        .filter(record -> query.statuses().contains(record.status()))
                        .ifPresent(matched::add);
            }
            if (page.size() <= query.limit()) {
                exhausted = true;
            } else {
                ConversationRecord last = page.getLast();
                cursor = Optional.of(new ConversationCursor(last.lastActivityAt(), last.sessionId()));
            }
        }
        boolean more = matched.size() > query.limit();
        List<ConversationRecord> items = more ? List.copyOf(matched.subList(0, query.limit())) : List.copyOf(matched);
        Optional<ConversationCursor> next = more
                ? Optional.of(new ConversationCursor(
                        items.getLast().lastActivityAt(), items.getLast().sessionId()))
                : Optional.empty();
        return new ConversationPage(items, next);
    }

    @Override
    public ConversationTurnPage turns(AgentSessionId sessionId, ConversationTurnQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        SdkCaller caller = caller();
        requireAuthorizedSession(Objects.requireNonNull(sessionId, "sessionId must not be null"), caller);
        List<ConversationTurn> result = new ArrayList<>();
        MessageCursor cursor =
                query.after().map(value -> new MessageCursor(value.sequence())).orElse(MessageCursor.BEFORE_FIRST);
        while (result.size() <= query.limit()) {
            var messages = persistence.runtimePersistence().state().messagesAfter(sessionId, cursor, MESSAGE_PAGE_SIZE);
            if (messages.isEmpty()) break;
            for (var message : messages) {
                cursor = message.cursor();
                if (message.visibility() != MessageVisibility.USER_VISIBLE) continue;
                if (message.role() != MessageRole.USER && message.role() != MessageRole.ASSISTANT) continue;
                var safeContents = message.contents().stream()
                        .filter(content -> content instanceof TextPart
                                || content instanceof io.haifa.agent.core.content.AssetRefPart
                                || content instanceof io.haifa.agent.core.content.ArtifactRefPart
                                || content instanceof io.haifa.agent.core.content.ImageUrlContentPart
                                || content instanceof io.haifa.agent.core.content.StoredImageContentPart
                                || content instanceof io.haifa.agent.core.content.StoredAudioContentPart)
                        .toList();
                if (!safeContents.isEmpty()) {
                    result.add(new ConversationTurn(
                            message.id().value(),
                            message.role(),
                            message.runId(),
                            message.sequence(),
                            safeContents,
                            message.visibility(),
                            message.createdAt()));
                    if (result.size() > query.limit()) break;
                }
            }
            if (result.size() > query.limit()) break;
            if (messages.size() < MESSAGE_PAGE_SIZE) break;
        }
        boolean more = result.size() > query.limit();
        List<ConversationTurn> items = more ? List.copyOf(result.subList(0, query.limit())) : List.copyOf(result);
        Optional<ConversationTurnCursor> next =
                more ? Optional.of(new ConversationTurnCursor(items.getLast().sequence())) : Optional.empty();
        return new ConversationTurnPage(items, next);
    }

    @Override
    public ConversationRun submit(SubmitConversationTurnCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        AgentSession session = requireAuthorizedSession(command.sessionId(), caller);
        String dispatchKey = dispatchKey("submit", callerScope(caller), keyDigest(command.idempotencyKey()));
        Optional<AgentRunId> recovered = idempotency().findRun(runtimeScope(caller), "start", dispatchKey);
        if (recovered.isPresent()) {
            if (!resolveSessionId(recovered.orElseThrow(), caller).equals(command.sessionId())) {
                throw conflict("CONVERSATION_UNAVAILABLE");
            }
            AgentRunSnapshot recoveredRun = startOrConflict(runRequest(
                    dispatchKey,
                    command.sessionId(),
                    command.message(),
                    command.runProfileId(),
                    command.inputs(),
                    command.structuredOutput()));
            return new ConversationRun(
                    requireAuthorizedRecord(command.sessionId(), caller), recoveredRun.runId(), recoveredRun.version());
        }
        if (session.status() != AgentSessionStatus.ACTIVE) {
            throw conflict("CONVERSATION_ARCHIVED");
        }
        return persistence.inTransaction(() -> {
            ConversationRecord current =
                    conversations.find(command.sessionId()).orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
            if (current.revision() != command.expectedRevision()) {
                throw conflict("CONVERSATION_REVISION_STALE");
            }
            AgentRunSnapshot run = runtime.start(runRequest(
                    dispatchKey,
                    command.sessionId(),
                    command.message(),
                    command.runProfileId(),
                    command.inputs(),
                    command.structuredOutput()));
            ConversationRecord touched = conversations.touchLastActivity(command.sessionId(), time.now());
            return new ConversationRun(withStatus(touched, session), run.runId(), run.version());
        });
    }

    private AgentRunSnapshot startOrConflict(AgentRunRequest request) {
        try {
            return runtime.start(request);
        } catch (io.haifa.agent.runtime.api.RuntimeContractException exception) {
            if (exception.code() == io.haifa.agent.runtime.api.RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT) {
                throw conflict("CONVERSATION_IDEMPOTENCY_CONFLICT");
            }
            throw exception;
        }
    }

    @Override
    public ConversationRecord rename(RenameConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        AgentSession session = requireAuthorizedSession(command.sessionId(), caller);
        String callerScope = callerScope(caller);
        String dispatchKey = dispatchKey("rename", callerScope, keyDigest(command.idempotencyKey()));
        String requestDigest = CanonicalSdkDigest.sha256(
                "conversation-command-v1",
                "rename",
                command.sessionId().value(),
                command.expectedRevision() + "\u0000" + command.displayName());
        Optional<AppliedCommandResult> applied = idempotency().findAppliedCommand(callerScope, "rename", dispatchKey);
        if (applied.isPresent()) {
            requireMatchingDigest(applied.orElseThrow(), requestDigest);
            return requireAuthorizedRecord(command.sessionId(), caller);
        }
        ConversationRecord current =
                conversations.find(command.sessionId()).orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
        if (current.revision() != command.expectedRevision()) {
            throw conflict("CONVERSATION_REVISION_STALE");
        }
        return persistence.inTransaction(() -> {
            ConversationRecord renamed = conversations.rename(
                    command.sessionId(), command.expectedRevision(), command.displayName(), time.now());
            idempotency()
                    .recordAppliedCommand(new AppliedCommandResult(
                            callerScope,
                            "rename",
                            dispatchKey,
                            Optional.of(requestDigest),
                            1,
                            Long.toString(renamed.revision()),
                            time.now()));
            return withStatus(renamed, session);
        });
    }

    @Override
    public ConversationRecord archive(ChangeConversationStatusCommand command) {
        return changeStatus(command, "archive", ConversationStatus.ARCHIVED);
    }

    @Override
    public ConversationRecord unarchive(ChangeConversationStatusCommand command) {
        return changeStatus(command, "unarchive", ConversationStatus.ACTIVE);
    }

    private ConversationRecord changeStatus(
            ChangeConversationStatusCommand command, String operation, ConversationStatus target) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        requireAuthorizedSession(command.sessionId(), caller);
        String callerScope = callerScope(caller);
        String dispatchKey = dispatchKey(operation, callerScope, keyDigest(command.idempotencyKey()));
        String requestDigest = CanonicalSdkDigest.sha256(
                "conversation-command-v1",
                operation,
                command.sessionId().value(),
                Long.toString(command.expectedRevision()));
        Optional<AppliedCommandResult> applied = idempotency().findAppliedCommand(callerScope, operation, dispatchKey);
        if (applied.isPresent()) {
            requireMatchingDigest(applied.orElseThrow(), requestDigest);
            return requireAuthorizedRecord(command.sessionId(), caller);
        }
        ConversationRecord current =
                conversations.find(command.sessionId()).orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
        if (current.revision() != command.expectedRevision()) {
            throw conflict("CONVERSATION_REVISION_STALE");
        }
        return persistence.inTransaction(() -> {
            AgentSession session = requireAuthorizedSession(command.sessionId(), caller);
            long sessionVersion = session.version();
            if (target == ConversationStatus.ARCHIVED) {
                session.archive(time.now());
            } else {
                session.unarchive(time.now());
            }
            sessions().save(session, sessionVersion);
            ConversationRecord changed =
                    conversations.changeStatus(command.sessionId(), command.expectedRevision(), time.now());
            idempotency()
                    .recordAppliedCommand(new AppliedCommandResult(
                            callerScope,
                            operation,
                            dispatchKey,
                            Optional.of(requestDigest),
                            1,
                            Long.toString(changed.revision()),
                            time.now()));
            return withStatus(changed, session);
        });
    }

    private static void requireMatchingDigest(AppliedCommandResult applied, String requestDigest) {
        if (applied.requestDigest().filter(requestDigest::equals).isEmpty()) {
            throw conflict("CONVERSATION_IDEMPOTENCY_CONFLICT");
        }
    }

    private AgentRunRequest runRequest(
            String dispatchKey,
            AgentSessionId sessionId,
            String message,
            Optional<String> runProfileId,
            List<io.haifa.agent.core.content.ContentPart> inputs,
            Optional<io.haifa.agent.core.run.StructuredOutputRequirement> structuredOutput) {
        return new AgentRunRequest(
                dispatchKey,
                profile.definitionId(),
                Optional.of(profile.definitionVersion()),
                runProfileId.orElse(profile.defaultRunProfile().id()),
                sessionId,
                Optional.empty(),
                message,
                inputs,
                RuntimeOverrides.NONE,
                structuredOutput);
    }

    private static ConversationRecord metadata(AgentSessionId sessionId, String displayName, Instant at) {
        return new ConversationRecord(sessionId, displayName, at, at, 0, ConversationStatus.ACTIVE);
    }

    private AgentSessionId resolveSessionId(AgentRunId runId, SdkCaller caller) {
        AgentRun run = persistence
                .runtimePersistence()
                .runs()
                .find(runId)
                .orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
        if (!run.tenant().equals(caller.tenant()) || !run.principal().equals(caller.principal())) {
            throw conflict("CONVERSATION_UNAVAILABLE");
        }
        return run.sessionId();
    }

    private Optional<ConversationRecord> visible(ConversationRecord metadata, SdkCaller caller) {
        return sessions()
                .find(metadata.sessionId())
                .filter(session -> isVisible(session, caller))
                .map(session -> withStatus(metadata, session));
    }

    private ConversationRecord requireAuthorizedRecord(AgentSessionId sessionId, SdkCaller caller) {
        ConversationRecord metadata =
                conversations.find(sessionId).orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
        AgentSession session = requireAuthorizedSession(sessionId, caller);
        return withStatus(metadata, session);
    }

    private AgentSession requireAuthorizedSession(AgentSessionId sessionId, SdkCaller caller) {
        AgentSession session = sessions()
                .find(Objects.requireNonNull(sessionId, "sessionId must not be null"))
                .orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
        if (!isVisible(session, caller)) throw conflict("CONVERSATION_UNAVAILABLE");
        return session;
    }

    private static boolean isVisible(AgentSession session, SdkCaller caller) {
        return session.tenant().equals(caller.tenant())
                && session.owner().equals(caller.principal())
                && (session.status() == AgentSessionStatus.ACTIVE || session.status() == AgentSessionStatus.ARCHIVED);
    }

    private static ConversationRecord withStatus(ConversationRecord metadata, AgentSession session) {
        return new ConversationRecord(
                metadata.sessionId(),
                metadata.displayName(),
                metadata.createdAt(),
                metadata.lastActivityAt(),
                metadata.revision(),
                session.status() == AgentSessionStatus.ARCHIVED
                        ? ConversationStatus.ARCHIVED
                        : ConversationStatus.ACTIVE);
    }

    private static String callerScope(SdkCaller caller) {
        return CanonicalSdkDigest.sha256(
                "caller-v1",
                caller.tenant().tenantId(),
                caller.principal().principalId(),
                caller.principal().principalType());
    }

    private static String keyDigest(String idempotencyKey) {
        return CanonicalSdkDigest.sha256("idempotency-v1", idempotencyKey);
    }

    private static String dispatchKey(String operation, String callerScope, String keyDigest) {
        return "sdk:" + operation + ":" + callerScope.substring(7, 23) + ":" + keyDigest.substring(7);
    }

    private static String runtimeScope(SdkCaller caller) {
        return caller.tenant().tenantId() + "|" + caller.principal().principalType() + "|"
                + caller.principal().principalId();
    }

    private io.haifa.agent.runtime.core.storage.AgentSessionRepository sessions() {
        return persistence.runtimePersistence().sessions();
    }

    private io.haifa.agent.runtime.core.storage.IdempotencyRepository idempotency() {
        return persistence.runtimePersistence().idempotency();
    }

    private SdkCaller caller() {
        return Objects.requireNonNull(callers.current(), "caller provider returned null");
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
    }
}
