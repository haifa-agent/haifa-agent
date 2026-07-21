package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipant;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRef;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;
import io.haifa.agent.runtime.core.bootstrap.EffectiveCapability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Runtime-owned deterministic coordinator for versioned capability checkpoint participants. */
public final class CapabilityCheckpointRegistry {
    private final Map<String, CapabilityCheckpointParticipant> participants;

    public CapabilityCheckpointRegistry(List<CapabilityCheckpointParticipant> participants) {
        Map<String, CapabilityCheckpointParticipant> indexed = new LinkedHashMap<>();
        participants.stream()
                .sorted(Comparator.comparing(value -> value.id().value()))
                .forEach(participant -> {
                    String key = participant.id().value();
                    if (indexed.putIfAbsent(key, participant) != null) {
                        throw new IllegalArgumentException("duplicate capability checkpoint participant: " + key);
                    }
                });
        this.participants = Map.copyOf(indexed);
    }

    public static CapabilityCheckpointRegistry empty() {
        return new CapabilityCheckpointRegistry(List.of());
    }

    public List<CapabilityCheckpointRef> capture(
            AgentRun run, List<EffectiveCapability> capabilities, String checkpointRef, Instant capturedAt) {
        Set<String> enabled = capabilityIds(capabilities);
        var context = new CapabilityCheckpointCaptureContext(
                run.id(), run.sessionId(), run.tenant(), run.principal(), enabled, checkpointRef, capturedAt);
        List<CapabilityCheckpointRef> references = new ArrayList<>();
        participants.values().stream()
                .filter(participant -> enabled.contains(participant.capabilityId()))
                .forEach(participant -> {
                    CapabilityCheckpointRef reference = Objects.requireNonNull(
                            participant.capture(context), "participant capture result must not be null");
                    requireIdentity(participant, reference);
                    references.add(reference);
                });
        return List.copyOf(references);
    }

    public void validateAndRestore(
            AgentRun run,
            List<EffectiveCapability> capabilities,
            List<CapabilityCheckpointRef> references,
            Instant restoredAt) {
        Set<String> enabled = capabilityIds(capabilities);
        var context = new CapabilityCheckpointRestoreContext(
                run.id(), run.sessionId(), run.tenant(), run.principal(), enabled, restoredAt);
        List<ResolvedParticipant> resolved = references.stream()
                .map(reference -> resolve(reference, enabled))
                .toList();
        resolved.forEach(value -> {
            var validation = value.participant().validate(value.reference(), context);
            if (!validation.valid()) {
                throw new CheckpointRestoreException(
                        CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                        validation.code() + ": " + validation.message());
            }
        });
        resolved.forEach(value -> {
            try {
                value.participant().restore(value.reference(), context);
            } catch (RuntimeException exception) {
                throw new CheckpointRestoreException(
                        CheckpointRestoreFailure.CAPABILITY_RESTORE_FAILED,
                        "capability restore failed after validation: "
                                + value.participant().id().value(),
                        exception);
            }
        });
    }

    private ResolvedParticipant resolve(CapabilityCheckpointRef reference, Set<String> enabled) {
        if (!enabled.contains(reference.capabilityId())) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                    "checkpoint capability is no longer enabled: " + reference.capabilityId());
        }
        CapabilityCheckpointParticipant participant =
                participants.get(reference.participantId().value());
        if (participant == null) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                    "capability checkpoint participant is unavailable: "
                            + reference.participantId().value());
        }
        requireIdentity(participant, reference);
        return new ResolvedParticipant(participant, reference);
    }

    private static void requireIdentity(
            CapabilityCheckpointParticipant participant, CapabilityCheckpointRef reference) {
        if (!participant.id().equals(reference.participantId())
                || !participant.version().equals(reference.participantVersion())
                || !participant.capabilityId().equals(reference.capabilityId())) {
            throw new CheckpointRestoreException(
                    CheckpointRestoreFailure.CAPABILITY_STATE_INVALID,
                    "capability checkpoint participant identity or version mismatch");
        }
    }

    private static Set<String> capabilityIds(List<EffectiveCapability> capabilities) {
        return capabilities.stream().map(EffectiveCapability::capabilityId).collect(Collectors.toUnmodifiableSet());
    }

    private record ResolvedParticipant(
            CapabilityCheckpointParticipant participant, CapabilityCheckpointRef reference) {}
}
