package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

public final class LoopDetectionGuard implements AgentLoopGuard {
    private final int maximumRepeats;

    public LoopDetectionGuard(int maximumRepeats) {
        if (maximumRepeats < 1) throw new IllegalArgumentException("maximumRepeats must be positive");
        this.maximumRepeats = maximumRepeats;
    }

    @Override
    public void check(AgentRun run, io.haifa.agent.runtime.core.loop.AgentLoopContext context) {
        var fingerprints = context.fingerprints();
        LoopDetectedException.Reason repeatedPattern = repeatedPattern(fingerprints);
        if (repeatedPattern == null) return;
        var progress = context.progressSignatures();
        if (context.failureClusterAttempts() == 0
                && context.hasMeaningfulProgress()
                && progress.size() >= maximumRepeats) {
            String current = progress.getLast();
            boolean stalled =
                    progress.stream().skip(progress.size() - maximumRepeats).allMatch(current::equals);
            if (stalled) {
                if (context.requestStallRecovery(repeatedPattern)) return;
                throw new LoopDetectedException(repeatedPattern);
            }
        }
    }

    private LoopDetectedException.Reason repeatedPattern(java.util.List<String> fingerprints) {
        if (fingerprints.size() >= maximumRepeats) {
            String latest = fingerprints.getLast();
            long repeats = fingerprints.stream()
                    .skip(Math.max(0, fingerprints.size() - maximumRepeats))
                    .filter(latest::equals)
                    .count();
            if (repeats == maximumRepeats) return LoopDetectedException.Reason.REPEATED_DECISION;
        }
        if (fingerprints.size() >= 4) {
            int size = fingerprints.size();
            if (fingerprints.get(size - 1).equals(fingerprints.get(size - 3))
                    && fingerprints.get(size - 2).equals(fingerprints.get(size - 4))) {
                return LoopDetectedException.Reason.ALTERNATING_DECISION;
            }
        }
        return null;
    }
}
