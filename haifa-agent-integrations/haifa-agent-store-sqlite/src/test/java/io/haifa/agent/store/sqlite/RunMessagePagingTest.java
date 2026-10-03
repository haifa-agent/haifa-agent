package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.*;
import io.haifa.agent.core.reference.*;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.*;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.message.RunMessageProjector;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunMessagePagingTest {
    @Test
    void pagesAcrossFilteredLegacyMessagesAndPreservesRanksAfterRedactionPruneAndReopen(@TempDir Path directory) {
        var protector = new AesGcmModelContinuationProtector(
                new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom());
        AgentRunId runId;
        try (var foundation =
                SqliteStoreFoundation.initialize(SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK)) {
            runId = SqliteAggregateTestData.prepareRun(foundation).id();
            var state = foundation.runtimeState(protector);
            AtomicInteger notifications = new AtomicInteger();
            state.registerMessageCommitListener(id -> notifications.incrementAndGet());
            foundation.unitOfWork().execute(() -> {
                state.appendSessionMessage(
                        draft("user", runId, MessageRole.USER, MessageVisibility.USER_VISIBLE, "input"));
                for (int i = 0; i < 20; i++)
                    state.appendSessionMessage(draft(
                            "internal-" + i, runId, MessageRole.ASSISTANT, MessageVisibility.INTERNAL, "internal"));
                state.appendSessionMessage(
                        draft("first", runId, MessageRole.ASSISTANT, MessageVisibility.USER_VISIBLE, "first"));
                assertThat(notifications).hasValue(0);
                return null;
            });
            assertThat(notifications).hasValue(1);
            state.saveFinalOutputAndMessage(
                    runId,
                    "second",
                    draft("second", runId, MessageRole.ASSISTANT, MessageVisibility.USER_VISIBLE, "second"));
            assertThat(notifications).hasValue(2);
            var page = RunMessageProjector.page(state, runId, 0, 1);
            assertThat(page.items()).singleElement().satisfies(message -> {
                assertThat(message.messageId()).isEqualTo("first");
                assertThat(message.messageIndex()).isEqualTo(1);
                assertThat(message.sequence()).isEqualTo(22);
            });
            assertThat(page.hasMore()).isTrue();
            assertThat(RunMessageProjector.page(state, runId, page.nextCursor().exclusiveSequence(), 1)
                            .items())
                    .singleElement()
                    .satisfies(message -> assertThat(message.messageIndex()).isEqualTo(2));
            foundation
                    .events()
                    .deleteBefore(
                            runId, foundation.events().headSequence(runId).orElseThrow() + 1, SqliteTestSupport.NOW);
            assertThat(foundation.events().eventsFor(runId)).isEmpty();
            // Removing the notification history simulates older messages written before this API existed.
            state.redactMessage(new AgentMessageId("first"));
            state.redactMessage(new AgentMessageId("internal-0"));
            assertThat(RunMessageProjector.page(state, runId, 0, 10).items())
                    .extracting(RunMessageView::messageIndex)
                    .containsExactly(1L, 2L);
            assertThatThrownBy(() -> foundation.unitOfWork().execute(() -> {
                        state.appendSessionMessage(draft(
                                "rollback", runId, MessageRole.ASSISTANT, MessageVisibility.USER_VISIBLE, "rollback"));
                        throw new IllegalStateException("synthetic rollback");
                    }))
                    .isInstanceOf(RuntimeException.class);
            assertThat(notifications).hasValue(2);
            assertThat(state.message(new AgentMessageId("rollback"))).isEmpty();
            assertThat(foundation.events().eventsFor(runId)).isEmpty();
        }
        try (var reopened =
                SqliteStoreFoundation.initialize(SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK)) {
            var ports = reopened.persistencePorts(protector);
            var owner = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", request -> {
                        throw new AssertionError("message reads must not invoke a Model");
                    })
                    .persistence(ports)
                    .callers(() ->
                            new RuntimeCallerContext(new TenantRef("tenant"), new PrincipalRef("principal", "user")))
                    .build();
            var full = owner.messages(runId, RunMessageCursor.beforeFirst(runId), 10);
            assertThat(full.items()).extracting(RunMessageView::text).containsExactly("[REDACTED]", "second");
            assertThat(full.items()).extracting(RunMessageView::messageIndex).containsExactly(1L, 2L);
            ports.state()
                    .appendSessionMessage(
                            draft("third", runId, MessageRole.ASSISTANT, MessageVisibility.USER_VISIBLE, "third"));
            assertThat(owner.messages(runId, RunMessageCursor.beforeFirst(runId), 10)
                            .items()
                            .subList(0, 2))
                    .isEqualTo(full.items());
            var other = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", request -> {
                        throw new AssertionError("message reads must not invoke a Model");
                    })
                    .persistence(ports)
                    .callers(() -> new RuntimeCallerContext(new TenantRef("tenant"), new PrincipalRef("other", "user")))
                    .build();
            assertThatThrownBy(() -> other.messages(runId, RunMessageCursor.beforeFirst(runId), 10))
                    .isInstanceOfSatisfying(RuntimeContractException.class, failure -> assertThat(failure.code())
                            .isEqualTo(RuntimeApiErrorCode.RUN_NOT_FOUND));
        }
    }

    private static SessionMessageDraft draft(
            String id, AgentRunId runId, MessageRole role, MessageVisibility visibility, String text) {
        return new SessionMessageDraft(
                new AgentMessageId(id),
                new AgentSessionId("session"),
                Optional.of(runId),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                visibility,
                List.of(new TextPart(text, "plain")),
                Map.of("reasoning", "synthetic-secret-reasoning", "runMessageProjected", true),
                Instant.parse("2026-10-03T00:00:00Z"));
    }
}
