package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.core.storage.RecentMessageWindow;
import io.haifa.agent.runtime.core.storage.SessionMessageRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Builds a safe, bounded Session history projection from the authoritative Runtime message store. */
public final class CodingSessionHistoryService {
    private static final int MAXIMUM_ITEMS = 100;
    private static final int READ_BATCH_SIZE = 100;
    private static final int MAXIMUM_SCANNED_MESSAGES = 2_000;
    private static final int MAXIMUM_BODY_CODE_POINTS = 8_000;

    private final Consumer<AgentSessionId> sessionAuthorizer;
    private final SessionMessageRepository messages;
    private final Function<AgentRunId, Optional<io.haifa.agent.runtime.api.AgentRunSnapshot>> runFinder;
    private final SecretRedactor redactor;

    public CodingSessionHistoryService(
            CodingSessionService sessions,
            SessionMessageRepository messages,
            AgentRuntime runtime,
            SecretRedactor redactor) {
        this(
                authorizer(sessions),
                messages,
                Objects.requireNonNull(runtime, "runtime must not be null")::find,
                redactor);
    }

    private static Consumer<AgentSessionId> authorizer(CodingSessionService sessions) {
        CodingSessionService checked = Objects.requireNonNull(sessions, "sessions must not be null");
        return checked::openSession;
    }

    CodingSessionHistoryService(
            Consumer<AgentSessionId> sessionAuthorizer,
            SessionMessageRepository messages,
            Function<AgentRunId, Optional<io.haifa.agent.runtime.api.AgentRunSnapshot>> runFinder,
            SecretRedactor redactor) {
        this.sessionAuthorizer = Objects.requireNonNull(sessionAuthorizer, "sessionAuthorizer must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.runFinder = Objects.requireNonNull(runFinder, "runFinder must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    public CodingSessionHistoryPage recent(AgentSessionId sessionId, int limit) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (limit < 1 || limit > MAXIMUM_ITEMS) {
            throw new IllegalArgumentException("history limit must be between 1 and " + MAXIMUM_ITEMS);
        }
        sessionAuthorizer.accept(sessionId);
        Optional<MessageCursor> latest = messages.latestMessageCursor(sessionId);
        if (latest.isEmpty()) return CodingSessionHistoryPage.empty(sessionId);

        List<AgentMessage> visible = new ArrayList<>();
        MessageCursor cursor = latest.orElseThrow();
        int scanned = 0;
        boolean earlier = false;
        while (cursor.value() > 0 && visible.size() < limit && scanned < MAXIMUM_SCANNED_MESSAGES) {
            int batchSize = Math.min(READ_BATCH_SIZE, MAXIMUM_SCANNED_MESSAGES - scanned);
            RecentMessageWindow window = messages.recentMessages(sessionId, cursor, batchSize);
            if (window.messages().isEmpty()) break;
            scanned += window.messages().size();
            window.messages().stream()
                    .filter(CodingSessionHistoryService::isVisibleConversationMessage)
                    .forEach(visible::add);
            long previous = window.from().value() - 1;
            cursor = new MessageCursor(Math.max(0, previous));
        }
        visible.sort(Comparator.comparingLong(AgentMessage::sequence));
        if (visible.size() > limit) {
            visible = new ArrayList<>(visible.subList(visible.size() - limit, visible.size()));
            earlier = true;
        }
        if (cursor.value() > 0) earlier = true;

        List<CodingSessionHistoryItem> projected = project(visible);
        if (projected.size() > limit) {
            projected = projected.subList(projected.size() - limit, projected.size());
            earlier = true;
        }
        return new CodingSessionHistoryPage(sessionId, projected, earlier);
    }

    private List<CodingSessionHistoryItem> project(List<AgentMessage> source) {
        Map<AgentRunId, Long> lastSequenceByRun = new HashMap<>();
        Map<AgentRunId, Boolean> assistantByRun = new HashMap<>();
        for (AgentMessage message : source) {
            message.runId().ifPresent(runId -> {
                lastSequenceByRun.merge(runId, message.sequence(), Math::max);
                if (isAssistant(message.role())) assistantByRun.put(runId, true);
            });
        }
        Map<AgentRunId, CodingSessionHistoryItem> failures = new LinkedHashMap<>();
        lastSequenceByRun.forEach((runId, sequence) -> runFinder
                .apply(runId)
                .filter(snapshot -> snapshot.status() == AgentRunStatus.FAILED)
                .filter(snapshot -> !assistantByRun.getOrDefault(runId, false))
                .flatMap(snapshot -> snapshot.error()
                        .map(error -> new CodingSessionHistoryItem(
                                "history-error-" + runId.value(),
                                CodingSessionHistoryItem.Kind.ERROR,
                                "Run failed",
                                failureBody(error),
                                snapshot.status().name(),
                                sequence,
                                snapshot.updatedAt())))
                .ifPresent(item -> failures.put(runId, item)));

        List<CodingSessionHistoryItem> projected = new ArrayList<>();
        for (AgentMessage message : source) {
            project(message).ifPresent(projected::add);
            message.runId()
                    .filter(runId -> lastSequenceByRun.get(runId) == message.sequence())
                    .map(failures::get)
                    .ifPresent(projected::add);
        }
        return List.copyOf(projected);
    }

    private Optional<CodingSessionHistoryItem> project(AgentMessage message) {
        Optional<CodingSessionHistoryItem.Kind> kind =
                switch (message.role()) {
                    case USER -> Optional.of(CodingSessionHistoryItem.Kind.USER);
                    case ASSISTANT, AGENT -> Optional.of(CodingSessionHistoryItem.Kind.ASSISTANT);
                    default -> Optional.empty();
                };
        String body = message.contents().stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .reduce((left, right) -> left + "\n" + right)
                .map(redactor::redact)
                .map(CodingSessionHistoryService::bounded)
                .orElse("")
                .strip();
        if (kind.isEmpty() || body.isEmpty()) return Optional.empty();
        CodingSessionHistoryItem.Kind value = kind.orElseThrow();
        return Optional.of(new CodingSessionHistoryItem(
                "history-message-" + message.id().value(),
                value,
                value == CodingSessionHistoryItem.Kind.USER ? "You" : "Assistant",
                body,
                message.status().name(),
                message.sequence(),
                message.createdAt()));
    }

    private String failureBody(io.haifa.agent.core.error.AgentError error) {
        String value = "[" + error.code().wireCode() + "] " + error.message();
        if (error.optionalDiagnosticId().isPresent()) {
            value += " · Diagnostic ID: " + error.optionalDiagnosticId().orElseThrow();
        }
        return bounded(redactor.redact(value));
    }

    private static boolean isVisibleConversationMessage(AgentMessage message) {
        return message.visibility() == MessageVisibility.USER_VISIBLE
                && message.status() != MessageStatus.DELETED
                && message.status() != MessageStatus.REDACTED
                && (message.role() == MessageRole.USER || isAssistant(message.role()));
    }

    private static boolean isAssistant(MessageRole role) {
        return role == MessageRole.ASSISTANT || role == MessageRole.AGENT;
    }

    private static String bounded(String value) {
        int[] codePoints = value.codePoints().limit(MAXIMUM_BODY_CODE_POINTS).toArray();
        String bounded = new String(codePoints, 0, codePoints.length);
        return codePoints.length < value.codePointCount(0, value.length()) ? bounded + "…" : bounded;
    }
}
