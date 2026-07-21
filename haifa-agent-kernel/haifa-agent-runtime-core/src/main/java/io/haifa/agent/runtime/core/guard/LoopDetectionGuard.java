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
        if (repeats == maximumRepeats) throw new IllegalStateException("repeated decision loop detected");
        if (fingerprints.size() >= 4) {
            int size = fingerprints.size();
            if (fingerprints.get(size - 1).equals(fingerprints.get(size - 3))
                    && fingerprints.get(size - 2).equals(fingerprints.get(size - 4))) {
                throw new IllegalStateException("alternating decision loop detected");
            }
        }
        var progress = context.progressSignatures();
        if (progress.size() >= maximumRepeats) {
            String current = progress.getLast();
            boolean stalled =
                    progress.stream().skip(progress.size() - maximumRepeats).allMatch(current::equals);
            if (stalled) throw new IllegalStateException("loop made no observable progress");
        }
    }
}
