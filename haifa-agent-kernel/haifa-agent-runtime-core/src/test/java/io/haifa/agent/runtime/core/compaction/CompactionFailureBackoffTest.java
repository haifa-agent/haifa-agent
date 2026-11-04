package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CompactionFailureBackoffTest {
    @Test
    void retentionUsesTheSameHistoryRepresentationAndRejectsTheFirstTokenAboveLimit() {
        var budget = new ContextBudgetBreakdown(20_000, 1000, 1000, 1000, 0, 17_000, 1000, 1000, 1200);
        assertThat(SemanticCompactionCoordinator.retentionLimit(budget)).isEqualTo(1200);
        SemanticCompactionCoordinator.requireWithinRetention(1200, budget);
        assertThatThrownBy(() -> SemanticCompactionCoordinator.requireWithinRetention(1201, budget))
                .isInstanceOf(ContextBuildException.class);
        var tight = new ContextBudgetBreakdown(2000, 400, 400, 100, 0, 1100, 0, 1000, 1100);
        assertThat(SemanticCompactionCoordinator.retentionLimit(tight)).isEqualTo(1100);
    }

    @Test
    void unchangedPrefixBacksOffAcrossRunsButScopesVersionsExpiryEvictionAndReopenDoNot() {
        var store = new InMemoryRuntimeStore();
        var ids = new AtomicInteger();
        var first = CompactionTestSupport.createRun(store, ids);
        var second = CompactionTestSupport.createRun(store, ids);
        var config = store.configuration(first.configurationSnapshot()).orElseThrow();
        var binding = new FrozenModelBinding(config, request -> null, List.of());
        var policy = CompressionPolicy.defaults();
        var scope = CompactionFailureBackoff.Scope.of(first, binding, policy, 0);
        var same = CompactionFailureBackoff.Scope.of(second, binding, policy, 0);
        assertThat(same).isEqualTo(scope);
        List<AgentMessage> source = store.messagesAfter(first.sessionId(), MessageCursor.BEFORE_FIRST, 1);
        var now = new AtomicReference<>(Instant.parse("2026-07-21T00:00:00Z"));
        var cache = new CompactionFailureBackoff(now::get, 60_000, 2);
        cache.record(scope, source);
        // A new Run appended a USER tail; it cannot make the failed historical prefix new.
        assertThat(cache.contains(same, store.messagesAfter(first.sessionId(), MessageCursor.BEFORE_FIRST, 10)))
                .isTrue();
        var alternatives = List.of(
                scope(new AgentSessionId("other"), scope.tenant(), scope.principal(), scope.bindingHash(), scope, 0),
                scope(scope.session(), new TenantRef("other"), scope.principal(), scope.bindingHash(), scope, 0),
                scope(
                        scope.session(),
                        scope.tenant(),
                        new PrincipalRef("other", "user"),
                        scope.bindingHash(),
                        scope,
                        0),
                scope(scope.session(), scope.tenant(), scope.principal(), "new-binding", scope, 0),
                scope(scope.session(), scope.tenant(), scope.principal(), scope.bindingHash(), scope, 1),
                new CompactionFailureBackoff.Scope(
                        scope.session(),
                        scope.tenant(),
                        scope.principal(),
                        scope.bindingHash(),
                        scope.modelDigest(),
                        policy.withTailTokenBounds(5, 50),
                        0));
        var changedModel = new CompactionFailureBackoff.Scope(
                scope.session(),
                scope.tenant(),
                scope.principal(),
                scope.bindingHash(),
                "changed-model-digest",
                policy,
                0);
        assertThat(cache.contains(changedModel, source)).isFalse();
        alternatives.forEach(other -> assertThat(cache.contains(other, source)).isFalse());
        assertThat(new CompactionFailureBackoff(now::get, 60_000, 2).contains(scope, source))
                .isFalse();
        now.set(now.get().plusMillis(60_000));
        assertThat(cache.contains(scope, source)).isFalse();
        cache.record(scope, source);
        cache.record(alternatives.get(0), source);
        cache.record(alternatives.get(1), source);
        assertThat(cache.contains(scope, source)).isFalse();
        cache.record(scope, source);
        store.redactMessage(source.getFirst().id());
        assertThat(cache.contains(scope, store.messagesAfter(first.sessionId(), MessageCursor.BEFORE_FIRST, 10)))
                .isFalse();
    }

    private static CompactionFailureBackoff.Scope scope(
            AgentSessionId session,
            TenantRef tenant,
            PrincipalRef principal,
            String binding,
            CompactionFailureBackoff.Scope base,
            long version) {
        return new CompactionFailureBackoff.Scope(
                session, tenant, principal, binding, base.modelDigest(), base.policy(), version);
    }
}
