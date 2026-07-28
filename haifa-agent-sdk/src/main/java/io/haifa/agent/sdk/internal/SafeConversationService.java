package io.haifa.agent.sdk.internal;

import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.conversation.ChangeConversationStatusCommand;
import io.haifa.agent.sdk.conversation.ConversationException;
import io.haifa.agent.sdk.conversation.ConversationPage;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.conversation.ConversationTurnPage;
import io.haifa.agent.sdk.conversation.ConversationTurnQuery;
import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Maps implementation failures to a stable, non-secret public Conversation error family. */
public final class SafeConversationService implements ConversationService {
    private final ConversationService delegate;
    private final AtomicBoolean closed;

    public SafeConversationService(ConversationService delegate, AtomicBoolean closed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.closed = Objects.requireNonNull(closed, "closed must not be null");
    }

    @Override
    public ConversationRecord start(StartConversationCommand command) {
        return execute(
                "conversation.start",
                command == null ? "null" : command.idempotencyKey(),
                () -> delegate.start(command));
    }

    @Override
    public Optional<ConversationRecord> find(AgentSessionId sessionId) {
        return execute(
                "conversation.find", sessionId == null ? "null" : sessionId.value(), () -> delegate.find(sessionId));
    }

    @Override
    public ConversationPage list(ConversationQuery query) {
        return execute("conversation.list", "query", () -> delegate.list(query));
    }

    @Override
    public ConversationTurnPage turns(AgentSessionId sessionId, ConversationTurnQuery query) {
        return execute(
                "conversation.turns",
                sessionId == null ? "null" : sessionId.value(),
                () -> delegate.turns(sessionId, query));
    }

    @Override
    public ConversationRecord submit(SubmitConversationTurnCommand command) {
        return execute(
                "conversation.submit",
                command == null ? "null" : command.idempotencyKey(),
                () -> delegate.submit(command));
    }

    @Override
    public ConversationRecord rename(RenameConversationCommand command) {
        return execute(
                "conversation.rename",
                command == null ? "null" : command.idempotencyKey(),
                () -> delegate.rename(command));
    }

    @Override
    public ConversationRecord archive(ChangeConversationStatusCommand command) {
        return execute(
                "conversation.archive",
                command == null ? "null" : command.idempotencyKey(),
                () -> delegate.archive(command));
    }

    @Override
    public ConversationRecord unarchive(ChangeConversationStatusCommand command) {
        return execute(
                "conversation.unarchive",
                command == null ? "null" : command.idempotencyKey(),
                () -> delegate.unarchive(command));
    }

    private <T> T execute(String operation, String correlationInput, Supplier<T> action) {
        String correlation = CanonicalSdkDigest.sha256("sdk-error-correlation-v1", operation, correlationInput)
                .substring(7, 23);
        if (closed.get()) {
            throw failure("AGENT_CLOSED", operation, correlation);
        }
        try {
            return action.get();
        } catch (HaifaAgentException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw failure("CONVERSATION_INVALID_REQUEST", operation, correlation);
        } catch (IllegalStateException exception) {
            String code = isSafeCode(exception.getMessage()) ? exception.getMessage() : "CONVERSATION_OPERATION_FAILED";
            throw failure(code, operation, correlation);
        } catch (RuntimeException exception) {
            throw failure("CONVERSATION_PERSISTENCE_OR_RECOVERY_FAILED", operation, correlation);
        }
    }

    private static ConversationException failure(String code, String operation, String correlation) {
        return new ConversationException(code, operation, correlation);
    }

    private static boolean isSafeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{2,127}");
    }
}
