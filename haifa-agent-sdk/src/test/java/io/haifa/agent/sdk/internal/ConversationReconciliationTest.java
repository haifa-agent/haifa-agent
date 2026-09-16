package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.conversation.ConversationRun;
import io.haifa.agent.sdk.conversation.InMemoryConversationStore;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConversationReconciliationTest {
    @Test
    void startRecoversAnAlreadyBoundRunAcrossTheCrashWindowExactlyOnce() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        AtomicInteger idSequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "reconcile-" + idSequence.incrementAndGet();
        var profile = SdkTestFixtures.profile("personal");
        var model = SdkTestFixtures.modelContribution();
        var persistence = SdkTestFixtures.persistenceContribution();
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
                        profile.defaultRunProfile().version(),
                        AgentRunType.CHAT,
                        profile.budget(),
                        profile.limits(),
                        model.snapshot()))
                .registerChatModel(
                        model.snapshot().adapterType(),
                        model.snapshot().adapterVersion(),
                        model.adapters().get(io.haifa.agent.model.api.ModelAdapterCoordinate.from(model.snapshot())))
                .build();
        var command = new StartConversationCommand("start-window", "Recovery", "hello");
        var firstStore = new InMemoryConversationStore();
        var firstService =
                new DefaultConversationService(profile, runtime, persistence, firstStore, () -> caller, ids, () -> now);
        ConversationRun first = firstService.start(command);
        assertThat(scheduler.pending()).isEqualTo(1);

        var recoveredStore = new InMemoryConversationStore();
        var recoveredService = new DefaultConversationService(
                profile, runtime, persistence, recoveredStore, () -> caller, ids, () -> now);
        ConversationRun recovered = recoveredService.start(command);

        assertThat(recovered.runId()).isEqualTo(first.runId());
        assertThat(recovered.record().sessionId()).isEqualTo(first.record().sessionId());
        assertThat(recoveredService.find(first.record().sessionId())).isPresent();
        assertThat(runtime.find(first.runId())).isPresent();
        assertThat(scheduler.pending()).isEqualTo(1);
    }
}
