package io.haifa.agent.runtime.core.loop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AgentLoopContext {
    private int iteration;
    private final List<String> fingerprints;
    private final Set<String> convergenceReasons = new LinkedHashSet<>();
    private final List<String> progressSignatures = new ArrayList<>();
    private int repairAttempts;
    private int forcedContextRebuildAttempts;

    public AgentLoopContext(int iteration, List<String> fingerprints) {
        this(iteration, fingerprints, 0);
    }

    public AgentLoopContext(int iteration, List<String> fingerprints, int forcedContextRebuildAttempts) {
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        if (forcedContextRebuildAttempts < 0 || forcedContextRebuildAttempts > 1) {
            throw new IllegalArgumentException("forced context rebuild attempts must be zero or one");
        }
        this.iteration = iteration;
        this.fingerprints = new ArrayList<>(fingerprints);
        this.forcedContextRebuildAttempts = forcedContextRebuildAttempts;
    }

    public int iteration() {
        return iteration;
    }

    public void next() {
        iteration++;
    }

    public void record(String fingerprint) {
        fingerprints.add(fingerprint);
    }

    public List<String> fingerprints() {
        return List.copyOf(fingerprints);
    }

    public void requestConvergence(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        convergenceReasons.add(reason.trim());
    }

    public List<String> convergenceReasons() {
        return List.copyOf(convergenceReasons);
    }

    public void recordProgress(String signature) {
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature must not be blank");
        progressSignatures.add(signature);
    }

    public List<String> progressSignatures() {
        return List.copyOf(progressSignatures);
    }

    public int recordRepairAttempt() {
        return ++repairAttempts;
    }

    public int repairAttempts() {
        return repairAttempts;
    }

    public int recordForcedContextRebuild() {
        if (forcedContextRebuildAttempts >= 1) {
            throw new ContextRebuildExhaustedException("model context remained too long after forced rebuild");
        }
        return ++forcedContextRebuildAttempts;
    }

    public int forcedContextRebuildAttempts() {
        return forcedContextRebuildAttempts;
    }
}
