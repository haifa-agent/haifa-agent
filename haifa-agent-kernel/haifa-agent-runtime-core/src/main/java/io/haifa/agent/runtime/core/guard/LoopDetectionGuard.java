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
        if (fingerprints.size() < maximumRepeats) return;
        String latest = fingerprints.getLast();
        long repeats = fingerprints.stream()
                .skip(Math.max(0, fingerprints.size() - maximumRepeats))
                .filter(latest::equals)
                .count();
        if (repeats == maximumRepeats) {
            throw new LoopDetectedException(LoopDetectedException.Reason.REPEATED_DECISION);
        }
        if (fingerprints.size() >= 4) {
            int size = fingerprints.size();
            if (fingerprints.get(size - 1).equals(fingerprints.get(size - 3))
                    && fingerprints.get(size - 2).equals(fingerprints.get(size - 4))) {
                throw new LoopDetectedException(LoopDetectedException.Reason.ALTERNATING_DECISION);
            }
        }
        var progress = context.progressSignatures();
        if (context.failureClusterAttempts() == 0
                && context.hasMeaningfulProgress()
                && progress.size() > maximumRepeats) {
            String current = progress.getLast();
            boolean stalled = progress.stream()
                    .skip(progress.size() - maximumRepeats - 1L)
                    .allMatch(current::equals);
            if (stalled) {
                throw new LoopDetectedException(LoopDetectedException.Reason.NO_OBSERVABLE_PROGRESS);
            }
        }
    }
}
