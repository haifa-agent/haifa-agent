package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.conversation.ConversationCommandBinding;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.conversation.InMemoryConversationStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConversationReconciliationTest {
    @Test
    void repairsCrashWindowAfterRuntimeCreatedRunBeforeConversationBinding() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        AtomicInteger idSequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "reconcile-" + idSequence.incrementAndGet();
        var profile = SdkTestFixtures.profile("personal", Map.of());
        var model = SdkTestFixtures.modelContribution();
        var persistence = SdkTestFixtures.persistenceContribution();
        var conversations = new InMemoryConversationStore();
        var scheduler = new ManualExecutionScheduler();
        SdkCaller caller = SdkCaller.defaultPublicUser();
        var runtime = new RuntimeCoreBuilder()
                .identifierGenerator(ids)
                .timeProvider(() -> now)
                .scheduler(scheduler)
                .persistence(persistence.runtimePersistence())
                .callers(() -> new RuntimeCallerContext(caller.tenant(), caller.principal()))
                .definitions((id, requested) -> new ResolvedDefinition(
                        id,
                        requested.orElse(profile.definitionVersion()),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        profile.instructions(),
                        List.of()))
                .profiles((id, overrides) -> new ResolvedProfile(
                        id,
                        profile.runProfileVersion(),
                        AgentRunType.CHAT,
                        profile.budget(),
                        profile.limits(),
                        model.snapshot()))
                .registerChatModel(
                        model.snapshot().adapterType(),
                        model.snapshot().adapterVersion(),
                        model.adapters().get(io.haifa.agent.model.api.ModelAdapterCoordinate.from(model.snapshot())))
                .build();
        AgentSessionId sessionId = new AgentSessionId("pending-session");
        String dispatchKey = "sdk:submit:pending";
        persistence
                .runtimePersistence()
                .sessions()
                .insert(AgentSession.open(
                        sessionId, caller.tenant(), caller.principal(), null, SessionScope.USER, now, Map.of()));
        conversations.reserveCommand(new ConversationCommandBinding(
                "caller-digest",
                "submit",
                "key-digest",
                "request-digest",
                dispatchKey,
                sessionId,
                Optional.empty(),
                false,
                OptionalLong.empty(),
                now));
        conversations.create(new ConversationRecord(
                sessionId,
                caller.tenant(),
                caller.principal(),
                "Pending",
                ConversationStatus.ACTIVE,
                Optional.empty(),
                OptionalLong.empty(),
                Optional.of(dispatchKey),
                now,
                now,
                0));
        var accepted = runtime.start(new AgentRunRequest(
                dispatchKey,
                profile.definitionId(),
                Optional.of(profile.definitionVersion()),
                profile.runProfileId(),
                sessionId,
                Optional.empty(),
                "message",
                List.of(),
                RuntimeOverrides.NONE));
        var service = new DefaultConversationService(
                profile, runtime, persistence, conversations, () -> caller, ids, () -> now);

        ConversationRecord repaired = service.find(sessionId).orElseThrow();

        assertThat(repaired.activeRunId()).contains(accepted.runId());
        assertThat(repaired.activeDispatchKey()).isEmpty();
        assertThat(conversations.findCommand(dispatchKey).orElseThrow().completed())
                .isTrue();
        assertThat(scheduler.pending()).isEqualTo(1);
    }
}
