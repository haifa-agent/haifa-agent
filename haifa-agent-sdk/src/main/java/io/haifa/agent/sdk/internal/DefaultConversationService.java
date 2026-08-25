package io.haifa.agent.sdk.internal;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.conversation.ChangeConversationStatusCommand;
import io.haifa.agent.sdk.conversation.ConversationCommandBinding;
import io.haifa.agent.sdk.conversation.ConversationCursor;
import io.haifa.agent.sdk.conversation.ConversationPage;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
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
import java.util.OptionalLong;

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
    public ConversationRecord start(StartConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        Instant now = time.now();
        AgentSessionId proposedSession = new AgentSessionId(ids.nextValue());
        ConversationCommandBinding proposal = commandBinding(
                caller,
                "start",
                command.idempotencyKey(),
                command.displayName()
                        + "\u0000"
                        + command.message()
                        + "\u0000"
                        + command.runProfileId().orElse("")
                        + "\u0000"
                        + inputSignature(command.inputs())
                        + "\u0000"
                        + structuredOutputSignature(command.structuredOutput()),
                proposedSession,
                now);
        ConversationCommandBinding binding = persistence.inTransaction(() -> {
            ConversationCommandBinding reserved = conversations.reserveCommand(proposal);
            AgentSessionId sessionId = reserved.sessionId();
            Optional<ConversationRecord> existing = conversations.find(sessionId);
            if (existing.isPresent()) {
                authorize(existing.orElseThrow(), caller);
                return reserved;
            }
            Optional<AgentSession> persistedSession =
                    persistence.runtimePersistence().sessions().find(sessionId);
            if (persistedSession.isPresent()) {
                authorize(persistedSession.orElseThrow(), caller);
            } else {
                AgentSession session = AgentSession.open(
                        sessionId,
                        caller.tenant(),
                        caller.principal(),
                        null,
                        SessionScope.USER,
                        now,
                        Map.of("productId", profile.productId().value()));
                persistence.runtimePersistence().sessions().insert(session);
            }
            conversations.create(new ConversationRecord(
                    sessionId,
                    caller.tenant(),
                    caller.principal(),
                    command.displayName(),
                    ConversationStatus.ACTIVE,
                    Optional.empty(),
                    OptionalLong.empty(),
                    Optional.of(reserved.dispatchKey()),
                    now,
                    now,
                    0));
            return reserved;
        });
        if (binding.completed()) {
            return requireAuthorized(binding.sessionId(), caller);
        }
        AgentRunSnapshot run = runtime.start(runRequest(
                binding, command.message(), command.runProfileId(), command.inputs(), command.structuredOutput()));
        return persistence.inTransaction(() -> {
            ConversationRecord activated = conversations.activateRun(
                    binding.sessionId(), binding.dispatchKey(), run.runId(), run.version(), time.now());
            conversations.completeCommand(binding.dispatchKey(), Optional.of(run.runId()), activated.revision());
            return activated;
        });
    }

    @Override
    public Optional<ConversationRecord> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        SdkCaller caller = caller();
        return conversations
                .find(sessionId)
                .filter(value -> value.tenant().equals(caller.tenant())
                        && value.principal().equals(caller.principal()))
                .map(this::reconcileTerminalRun);
    }

    @Override
    public ConversationPage list(ConversationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        SdkCaller caller = caller();
        List<ConversationRecord> raw = conversations.list(caller.tenant(), caller.principal(), query);
        List<ConversationRecord> reconciled =
                raw.stream().map(this::reconcileTerminalRun).toList();
        boolean more = reconciled.size() > query.limit();
        List<ConversationRecord> items =
                more ? List.copyOf(reconciled.subList(0, query.limit())) : List.copyOf(reconciled);
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
        requireAuthorized(Objects.requireNonNull(sessionId, "sessionId must not be null"), caller);
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
    public ConversationRecord submit(SubmitConversationTurnCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        ConversationRecord current = reconcileTerminalRun(requireAuthorized(command.sessionId(), caller));
        if (current.status() != ConversationStatus.ACTIVE) {
            throw conflict("CONVERSATION_ARCHIVED");
        }
        Instant now = time.now();
        ConversationCommandBinding proposal = commandBinding(
                caller,
                "submit",
                command.idempotencyKey(),
                command.message() + "\u0000" + command.runProfileId().orElse("") + "\u0000"
                        + inputSignature(command.inputs()) + "\u0000"
                        + structuredOutputSignature(command.structuredOutput()),
                command.sessionId(),
                now);
        ConversationCommandBinding binding = persistence.inTransaction(() -> {
            ConversationCommandBinding reserved = conversations.reserveCommand(proposal);
            if (reserved.completed()) return reserved;
            ConversationRecord latest = requireAuthorized(command.sessionId(), caller);
            if (latest.activeDispatchKey()
                    .filter(reserved.dispatchKey()::equals)
                    .isEmpty()) {
                conversations.reserveActive(
                        command.sessionId(), command.expectedRevision(), reserved.dispatchKey(), now);
            }
            return reserved;
        });
        if (binding.completed()) {
            return reconcileTerminalRun(requireAuthorized(command.sessionId(), caller));
        }
        AgentRunSnapshot run = runtime.start(runRequest(
                binding, command.message(), command.runProfileId(), command.inputs(), command.structuredOutput()));
        return persistence.inTransaction(() -> {
            ConversationRecord activated = conversations.activateRun(
                    binding.sessionId(), binding.dispatchKey(), run.runId(), run.version(), time.now());
            conversations.completeCommand(binding.dispatchKey(), Optional.of(run.runId()), activated.revision());
            return activated;
        });
    }

    @Override
    public ConversationRecord rename(RenameConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        requireAuthorized(command.sessionId(), caller);
        ConversationCommandBinding binding = conversations.reserveCommand(commandBinding(
                caller,
                "rename",
                command.idempotencyKey(),
                command.expectedRevision() + "\u0000" + command.displayName(),
                command.sessionId(),
                time.now()));
        if (binding.completed()) return requireAuthorized(command.sessionId(), caller);
        return persistence.inTransaction(() -> {
            ConversationRecord renamed = conversations.rename(
                    command.sessionId(), command.expectedRevision(), command.displayName(), time.now());
            conversations.completeCommand(binding.dispatchKey(), Optional.empty(), renamed.revision());
            return renamed;
        });
    }

    @Override
    public ConversationRecord archive(ChangeConversationStatusCommand command) {
        return changeStatus(command, "archive", ConversationStatus.ACTIVE, ConversationStatus.ARCHIVED);
    }

    @Override
    public ConversationRecord unarchive(ChangeConversationStatusCommand command) {
        return changeStatus(command, "unarchive", ConversationStatus.ARCHIVED, ConversationStatus.ACTIVE);
    }

    private ConversationRecord changeStatus(
            ChangeConversationStatusCommand command,
            String operation,
            ConversationStatus expected,
            ConversationStatus target) {
        Objects.requireNonNull(command, "command must not be null");
        SdkCaller caller = caller();
        ConversationRecord current = reconcileTerminalRun(requireAuthorized(command.sessionId(), caller));
        ConversationCommandBinding binding = conversations.reserveCommand(commandBinding(
                caller,
                operation,
                command.idempotencyKey(),
                Long.toString(command.expectedRevision()),
                command.sessionId(),
                time.now()));
        if (binding.completed()) return requireAuthorized(command.sessionId(), caller);
        if (current.revision() != command.expectedRevision()) {
            throw conflict("CONVERSATION_REVISION_STALE");
        }
        return persistence.inTransaction(() -> {
            AgentSession session = persistence
                    .runtimePersistence()
                    .sessions()
                    .find(command.sessionId())
                    .orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
            authorize(session, caller);
            long sessionVersion = session.version();
            if (target == ConversationStatus.ARCHIVED) {
                session.archive(time.now());
            } else {
                session.unarchive(time.now());
            }
            persistence.runtimePersistence().sessions().save(session, sessionVersion);
            ConversationRecord changed = conversations.changeStatus(
                    command.sessionId(), command.expectedRevision(), expected, target, time.now());
            conversations.completeCommand(binding.dispatchKey(), Optional.empty(), changed.revision());
            return changed;
        });
    }

    private ConversationRecord reconcileTerminalRun(ConversationRecord conversation) {
        conversation = reconcilePendingDispatch(conversation);
        if (conversation.activeRunId().isEmpty()) return conversation;
        AgentRunSnapshot snapshot =
                runtime.find(conversation.activeRunId().orElseThrow()).orElse(null);
        if (snapshot == null || !snapshot.status().isTerminal()) return conversation;
        try {
            return conversations.clearActive(
                    conversation.sessionId(), snapshot.runId(), conversation.revision(), time.now());
        } catch (IllegalStateException stale) {
            return conversations.find(conversation.sessionId()).orElseThrow();
        }
    }

    private ConversationRecord reconcilePendingDispatch(ConversationRecord conversation) {
        if (conversation.activeDispatchKey().isEmpty()) return conversation;
        String dispatchKey = conversation.activeDispatchKey().orElseThrow();
        ConversationCommandBinding binding =
                conversations.findCommand(dispatchKey).orElse(null);
        if (binding == null || !binding.sessionId().equals(conversation.sessionId())) {
            return conversation;
        }
        Optional<io.haifa.agent.core.run.AgentRunId> recovered = binding.runId();
        if (recovered.isEmpty()) {
            String runtimeScope = conversation.tenant().tenantId()
                    + "|"
                    + conversation.principal().principalType()
                    + "|"
                    + conversation.principal().principalId();
            recovered = persistence.runtimePersistence().idempotency().findRun(runtimeScope, "start", dispatchKey);
        }
        if (recovered.isEmpty()) return conversation;
        AgentRunSnapshot snapshot = runtime.find(recovered.orElseThrow()).orElse(null);
        if (snapshot == null) return conversation;
        ConversationRecord pending = conversation;
        try {
            return persistence.inTransaction(() -> {
                ConversationRecord activated = conversations.activateRun(
                        pending.sessionId(), dispatchKey, snapshot.runId(), snapshot.version(), time.now());
                conversations.completeCommand(dispatchKey, Optional.of(snapshot.runId()), activated.revision());
                return activated;
            });
        } catch (IllegalStateException stale) {
            return conversations.find(pending.sessionId()).orElseThrow();
        }
    }

    private AgentRunRequest runRequest(
            ConversationCommandBinding binding,
            String message,
            Optional<String> runProfileId,
            List<io.haifa.agent.core.content.ContentPart> inputs,
            Optional<io.haifa.agent.core.run.StructuredOutputRequirement> structuredOutput) {
        return new AgentRunRequest(
                binding.dispatchKey(),
                profile.definitionId(),
                Optional.of(profile.definitionVersion()),
                runProfileId.orElse(profile.runProfileId()),
                binding.sessionId(),
                Optional.empty(),
                message,
                inputs,
                RuntimeOverrides.NONE,
                structuredOutput);
    }

    private static String structuredOutputSignature(
            Optional<io.haifa.agent.core.run.StructuredOutputRequirement> requirement) {
        return requirement
                .map(value -> CanonicalSdkDigest.sha256(
                        "structured-output-v1",
                        value.schemaId(),
                        value.schemaVersion(),
                        value.responseName(),
                        canonicalValue(value.jsonSchema())))
                .orElse("");
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            iterable.forEach(item -> items.add(canonicalValue(item)));
            return String.join(",", items);
        }
        return String.valueOf(value);
    }

    private static String inputSignature(List<io.haifa.agent.core.content.ContentPart> inputs) {
        return inputs.stream()
                .map(value -> switch (value) {
                    case io.haifa.agent.core.content.ImageUrlContentPart image ->
                        "url:" + image.url().toASCIIString();
                    case io.haifa.agent.core.content.StoredImageContentPart image ->
                        String.join(
                                ":",
                                "stored",
                                image.storeId(),
                                image.imageId(),
                                image.mediaType(),
                                Long.toString(image.sizeBytes()),
                                image.sha256());
                    case io.haifa.agent.core.content.StoredAudioContentPart audio ->
                        String.join(
                                ":",
                                "stored-audio",
                                audio.storeId(),
                                audio.audioId(),
                                audio.mediaType(),
                                Long.toString(audio.sizeBytes()),
                                audio.sha256());
                    default -> throw new IllegalArgumentException("unsupported conversation input");
                })
                .collect(java.util.stream.Collectors.joining("\u0000"));
    }

    private ConversationCommandBinding commandBinding(
            SdkCaller caller,
            String operation,
            String idempotencyKey,
            String message,
            AgentSessionId sessionId,
            Instant at) {
        String callerScope = CanonicalSdkDigest.sha256(
                "caller-v1",
                caller.tenant().tenantId(),
                caller.principal().principalId(),
                caller.principal().principalType());
        String keyDigest = CanonicalSdkDigest.sha256("idempotency-v1", idempotencyKey);
        String sessionBinding = operation.equals("start") ? "" : sessionId.value();
        String requestDigest = CanonicalSdkDigest.sha256("conversation-command-v1", operation, sessionBinding, message);
        String dispatchKey = "sdk:" + operation + ":" + callerScope.substring(7, 23) + ":" + keyDigest.substring(7);
        return new ConversationCommandBinding(
                callerScope,
                operation,
                keyDigest,
                requestDigest,
                dispatchKey,
                sessionId,
                Optional.empty(),
                false,
                OptionalLong.empty(),
                at);
    }

    private ConversationRecord requireAuthorized(AgentSessionId sessionId, SdkCaller caller) {
        ConversationRecord conversation =
                conversations.find(sessionId).orElseThrow(() -> conflict("CONVERSATION_UNAVAILABLE"));
        authorize(conversation, caller);
        return conversation;
    }

    private static void authorize(ConversationRecord conversation, SdkCaller caller) {
        if (!conversation.tenant().equals(caller.tenant())
                || !conversation.principal().equals(caller.principal())) {
            throw conflict("CONVERSATION_UNAVAILABLE");
        }
    }

    private static void authorize(AgentSession session, SdkCaller caller) {
        if (!session.tenant().equals(caller.tenant()) || !session.owner().equals(caller.principal())) {
            throw conflict("CONVERSATION_UNAVAILABLE");
        }
        if (session.status() != AgentSessionStatus.ACTIVE && session.status() != AgentSessionStatus.ARCHIVED) {
            throw conflict("CONVERSATION_UNAVAILABLE");
        }
    }

    private SdkCaller caller() {
        return Objects.requireNonNull(callers.current(), "caller provider returned null");
    }

    private static IllegalStateException conflict(String code) {
        return new IllegalStateException(code);
    }
}
