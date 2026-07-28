package io.haifa.agent.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.conversation.ChangeConversationStatusCommand;
import io.haifa.agent.sdk.conversation.ConversationQuery;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.RenameConversationCommand;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.conversation.SubmitConversationTurnCommand;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HaifaAgentFacadeTest {
    @Test
    void completesMultiRunConversationAndLifecycleCommands() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "sdk-test-" + ids.incrementAndGet();
        try (HaifaAgent agent = HaifaAgents.builder()
                .product(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .identifierGenerator(identifiers)
                .timeProvider(() -> Instant.parse("2026-07-28T00:00:00Z"))
                .build()) {
            var started = agent.conversations().start(new StartConversationCommand("start-1", "First chat", "hello"));
            var duplicate = agent.conversations().start(new StartConversationCommand("start-1", "First chat", "hello"));

            assertThat(duplicate.sessionId()).isEqualTo(started.sessionId());
            agent.runs().await(started.activeRunId().orElseThrow());
            var idle = agent.conversations().find(started.sessionId()).orElseThrow();
            assertThat(idle.activeRunId()).isEmpty();

            var submitted = agent.conversations()
                    .submit(new SubmitConversationTurnCommand(idle.sessionId(), idle.revision(), "turn-2", "continue"));
            agent.runs().await(submitted.activeRunId().orElseThrow());
            var afterSecondRun = agent.conversations().find(started.sessionId()).orElseThrow();
            assertThat(agent.conversations().turns(started.sessionId()))
                    .extracting("text")
                    .containsExactly("hello", "answer-1", "continue", "answer-2");

            var renamed = agent.conversations()
                    .rename(new RenameConversationCommand(
                            started.sessionId(), afterSecondRun.revision(), "rename-1", "Renamed"));
            var renameRetry = agent.conversations()
                    .rename(new RenameConversationCommand(
                            started.sessionId(), afterSecondRun.revision(), "rename-1", "Renamed"));
            assertThat(renameRetry.displayName()).isEqualTo(renamed.displayName());

            var archived = agent.conversations()
                    .archive(new ChangeConversationStatusCommand(started.sessionId(), renamed.revision(), "archive-1"));
            var archiveRetry = agent.conversations()
                    .archive(new ChangeConversationStatusCommand(started.sessionId(), renamed.revision(), "archive-1"));
            assertThat(archiveRetry.status()).isEqualTo(ConversationStatus.ARCHIVED);
            var restored = agent.conversations()
                    .unarchive(new ChangeConversationStatusCommand(
                            started.sessionId(), archived.revision(), "unarchive-1"));

            assertThat(restored.status()).isEqualTo(ConversationStatus.ACTIVE);
            assertThat(agent.conversations().list(ConversationQuery.active(10)).items())
                    .extracting("sessionId")
                    .containsExactly(started.sessionId());
            assertThat(agent.assembly().profile().productId().value()).isEqualTo("personal");
        }
    }

    @Test
    void callerScopeDoesNotRevealAnotherPrincipalsConversation() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        AtomicReference<SdkCaller> caller =
                new AtomicReference<>(new SdkCaller(new TenantRef("tenant"), new PrincipalRef("alice", "user")));
        try (HaifaAgent agent = HaifaAgents.builder(SdkTestFixtures.profile("personal", Map.of()))
                .contributeAll(SdkTestFixtures.baseContributions())
                .callerProvider(caller::get)
                .identifierGenerator(() -> "scope-test-" + ids.incrementAndGet())
                .timeProvider(() -> Instant.parse("2026-07-28T00:00:00Z"))
                .build()) {
            var conversation =
                    agent.conversations().start(new StartConversationCommand("start", "Private", "secret text"));
            agent.runs().await(conversation.activeRunId().orElseThrow());
            caller.set(new SdkCaller(new TenantRef("tenant"), new PrincipalRef("bob", "user")));

            assertThat(agent.conversations().find(conversation.sessionId())).isEmpty();
            assertThat(agent.conversations().list(ConversationQuery.active(10)).items())
                    .isEmpty();
            assertThatThrownBy(() -> agent.conversations().turns(conversation.sessionId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CONVERSATION_UNAVAILABLE");
        }
    }
}
