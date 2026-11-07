package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Transient suppression of a failed prefix; no transcript bodies or durable state. */
final class CompactionFailureBackoff {
    record Scope(
            AgentSessionId session,
            TenantRef tenant,
            PrincipalRef principal,
            String bindingHash,
            String modelDigest,
            CompressionPolicy policy,
            long summaryVersion,
            CompactionTriggerReason reason) {
        static Scope of(
                AgentRun run,
                FrozenModelBinding binding,
                CompressionPolicy policy,
                long version,
                CompactionTriggerReason reason) {
            return new Scope(
                    run.sessionId(),
                    run.tenant(),
                    run.principal(),
                    binding.configuration().reference().contentHash(),
                    binding.configuration().model().configurationDigest(),
                    policy,
                    version,
                    reason);
        }
    }

    private record Failure(long through, int count, String digest, long expiresAt, OptionalLong retryAtTokens) {}

    private final TimeProvider time;
    private final long ttlMillis;
    private final int capacity;
    private final Map<Scope, Failure> failures = new LinkedHashMap<>(16, 0.75f, true);

    CompactionFailureBackoff(TimeProvider time, long ttlMillis, int capacity) {
        if (ttlMillis <= 0 || capacity < 1) throw new IllegalArgumentException("invalid backoff bounds");
        this.time = time;
        this.ttlMillis = ttlMillis;
        this.capacity = capacity;
    }

    boolean contains(Scope scope, List<AgentMessage> active) {
        return contains(scope, active, OptionalLong.empty());
    }

    synchronized boolean contains(Scope scope, List<AgentMessage> active, OptionalLong removablePrefixTokens) {
        expire();
        Failure failure = failures.get(scope);
        if (failure == null) return false;
        List<AgentMessage> prefix = active.stream()
                .takeWhile(m -> m.sequence() <= failure.through())
                .toList();
        if (prefix.size() != failure.count() || !digest(prefix).equals(failure.digest())) {
            failures.remove(scope);
            return false;
        }
        return failure.retryAtTokens().isEmpty()
                || (removablePrefixTokens.isPresent()
                        && removablePrefixTokens.getAsLong()
                                < failure.retryAtTokens().getAsLong());
    }

    synchronized void record(Scope scope, List<AgentMessage> source) {
        record(scope, source, OptionalLong.empty());
    }

    synchronized void recordNoReduction(Scope scope, List<AgentMessage> source, long retryAtPrefixTokens) {
        record(scope, source, OptionalLong.of(retryAtPrefixTokens));
    }

    private void record(Scope scope, List<AgentMessage> source, OptionalLong retryAtPrefixTokens) {
        expire();
        if (source.isEmpty()) return;
        failures.put(
                scope,
                new Failure(
                        source.getLast().sequence(),
                        source.size(),
                        digest(source),
                        Math.addExact(time.now().toEpochMilli(), ttlMillis),
                        retryAtPrefixTokens));
        while (failures.size() > capacity)
            failures.remove(failures.keySet().iterator().next());
    }

    private void expire() {
        long now = time.now().toEpochMilli();
        failures.values().removeIf(f -> now >= f.expiresAt());
    }

    private static String digest(List<AgentMessage> source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AgentMessage message : source) {
                digest.update((message.id().value() + "@" + message.sequence() + ":" + message.status() + ":"
                                + message.visibility() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
