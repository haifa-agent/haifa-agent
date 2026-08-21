package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.guard.LoopDetectedException;
import io.haifa.agent.runtime.core.recovery.ProgressLedger;
import io.haifa.agent.runtime.core.recovery.RecoveryController;
import io.haifa.agent.runtime.core.recovery.RecoveryDirective;
import io.haifa.agent.runtime.core.recovery.RunBudgetSnapshot;
import io.haifa.agent.runtime.core.recovery.ToolOutcomeClassifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AgentLoopContext {
    private int iteration;
    private final List<String> fingerprints;
    private final List<String> progressSignatures = new ArrayList<>();
    private int repairAttempts;
    private int forcedContextRebuildAttempts;
    private final ProgressLedger progressLedger = new ProgressLedger();
    private final RecoveryController recovery = new RecoveryController();
    private final ToolOutcomeClassifier outcomeClassifier = new ToolOutcomeClassifier();
    private final Set<String> processedToolOutcomes = new LinkedHashSet<>();
    private final Set<Integer> issuedBudgetThresholds = new LinkedHashSet<>();
    private RunBudgetSnapshot budgetSnapshot;
    private RecoveryDirective pendingRecovery;
    private int stallRecoveryAttempts;
    private StallRecoverySignal activeStallRecovery;
    private boolean stallRecoveryAnnouncementPending;

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

    public void recordProgress(String signature) {
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature must not be blank");
        progressSignatures.add(signature);
    }

    public List<String> progressSignatures() {
        return List.copyOf(progressSignatures);
    }

    public boolean hasMeaningfulProgress() {
        return progressLedger.hasMeaningfulProgress();
    }

    public void rebuildControlState(
            List<ToolCall> toolCalls,
            Optional<AgentPlan> plan,
            long childRuns,
            boolean restored,
            RunBudgetSnapshot snapshot) {
        for (ToolCall call : toolCalls.stream()
                .sorted(java.util.Comparator.comparing(ToolCall::requestedAt))
                .toList()) {
            if (!terminal(call.status())) continue;
            boolean progressObserved = progressLedger.observe(call);
            if (progressObserved) {
                recovery.meaningfulProgress();
                pendingRecovery = null;
                clearActiveStallRecovery();
            }
            outcomeClassifier.classify(call).ifPresent(observation -> {
                RecoveryController.Update update = recovery.observe(observation);
                pendingRecovery = update.directive();
            });
            processedToolOutcomes.add(outcomeKey(call));
        }
        progressLedger.observePlan(plan);
        progressLedger.observeChildResults(childRuns);
        if (restored) issuedBudgetThresholds.addAll(snapshot.crossedThresholds());
        budgetSnapshot = snapshot;
        if (!toolCalls.isEmpty() || plan.isPresent() || childRuns > 0) {
            progressSignatures.add(progressLedger.digest());
        }
    }

    public ControlObservation observeAuthoritativeState(
            List<ToolCall> toolCalls, Optional<AgentPlan> plan, long childRuns) {
        boolean progressObserved = progressLedger.observePlan(plan);
        progressObserved |= progressLedger.observeChildResults(childRuns);
        if (progressObserved) {
            recovery.meaningfulProgress();
            pendingRecovery = null;
            clearActiveStallRecovery();
        }
        List<RecoveryController.Update> updates = new ArrayList<>();
        for (ToolCall call : toolCalls.stream()
                .sorted(java.util.Comparator.comparing(ToolCall::requestedAt))
                .toList()) {
            if (!terminal(call.status()) || processedToolOutcomes.contains(outcomeKey(call))) continue;
            boolean callProgress = progressLedger.observe(call);
            progressObserved |= callProgress;
            if (callProgress) {
                recovery.meaningfulProgress();
                pendingRecovery = null;
                clearActiveStallRecovery();
            }
            outcomeClassifier.classify(call).ifPresent(observation -> {
                RecoveryController.Update update = recovery.observe(observation);
                updates.add(update);
                pendingRecovery = update.directive();
            });
            processedToolOutcomes.add(outcomeKey(call));
        }
        if (progressObserved && updates.isEmpty()) {
            recovery.meaningfulProgress();
            pendingRecovery = null;
            clearActiveStallRecovery();
        }
        return new ControlObservation(progressObserved, progressLedger.digest(), List.copyOf(updates));
    }

    public Optional<String> observeInteractions(List<String> stableResponseIds) {
        boolean progressObserved = false;
        for (String stableResponseId : stableResponseIds) {
            progressObserved |= progressLedger.observeInteraction(stableResponseId);
        }
        if (!progressObserved) return Optional.empty();
        recovery.meaningfulProgress();
        pendingRecovery = null;
        clearActiveStallRecovery();
        String digest = progressLedger.digest();
        progressSignatures.add(digest);
        return Optional.of(digest);
    }

    public Set<Integer> updateBudgetSnapshot(RunBudgetSnapshot snapshot) {
        budgetSnapshot = snapshot;
        Set<Integer> newlyCrossed = new LinkedHashSet<>(snapshot.crossedThresholds());
        newlyCrossed.removeAll(issuedBudgetThresholds);
        issuedBudgetThresholds.addAll(newlyCrossed);
        return Set.copyOf(newlyCrossed);
    }

    public Optional<RunBudgetSnapshot> budgetSnapshot() {
        return Optional.ofNullable(budgetSnapshot);
    }

    public int failureClusterAttempts() {
        return recovery.activeAttempts();
    }

    public String controlPrompt() {
        StringBuilder text = new StringBuilder();
        if (budgetSnapshot != null) text.append(budgetSnapshot.promptText());
        text.append(modelControlPrompt());
        return text.toString();
    }

    /** Provider-visible recovery guidance; exact remaining budgets stay in trace events. */
    public String modelControlPrompt() {
        StringBuilder text = new StringBuilder();
        recovery.activeCategory().ifPresent(category -> text.append(" Active failure cluster: category=")
                .append(category.name())
                .append(", attempts=")
                .append(recovery.activeAttempts())
                .append('.'));
        if (pendingRecovery != null) {
            text.append(" Recovery directive=")
                    .append(pendingRecovery.name())
                    .append(": ")
                    .append(pendingRecovery.guidance());
        }
        if (activeStallRecovery != null) {
            text.append(" Stall recovery directive=REQUIRE_STRATEGY_CHANGE: choose one different semantic action")
                    .append(" without changing provider, permissions, or policy; progressDigest=")
                    .append(activeStallRecovery.progressDigest())
                    .append("; pattern=")
                    .append(activeStallRecovery.reason().name())
                    .append('.');
        }
        return text.toString();
    }

    public boolean requestStallRecovery(LoopDetectedException.Reason reason) {
        if (stallRecoveryAttempts >= 1) return false;
        String progressDigest = progressSignatures.isEmpty() ? progressLedger.digest() : progressSignatures.getLast();
        stallRecoveryAttempts = 1;
        activeStallRecovery = new StallRecoverySignal(reason, progressDigest, stallRecoveryAttempts);
        stallRecoveryAnnouncementPending = true;
        return true;
    }

    public Optional<StallRecoverySignal> takeStallRecoveryAnnouncement() {
        if (!stallRecoveryAnnouncementPending || activeStallRecovery == null) return Optional.empty();
        stallRecoveryAnnouncementPending = false;
        return Optional.of(activeStallRecovery);
    }

    public void restoreStallRecoveryAttempts(int attempts) {
        if (attempts < stallRecoveryAttempts || attempts > 1) {
            throw new IllegalArgumentException("stall recovery attempts must be zero or one and not move backwards");
        }
        stallRecoveryAttempts = attempts;
    }

    public int stallRecoveryAttempts() {
        return stallRecoveryAttempts;
    }

    public int recordRepairAttempt() {
        return ++repairAttempts;
    }

    public int repairAttempts() {
        return repairAttempts;
    }

    public void restoreRepairAttempts(int attempts) {
        if (attempts < repairAttempts) {
            throw new IllegalArgumentException("repair attempts must not move backwards");
        }
        repairAttempts = attempts;
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

    private static boolean terminal(ToolCallStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, DENIED, CANCELLED, TIMEOUT -> true;
            default -> false;
        };
    }

    private static String outcomeKey(ToolCall call) {
        return call.id().value() + ":" + call.version();
    }

    private void clearActiveStallRecovery() {
        activeStallRecovery = null;
        stallRecoveryAnnouncementPending = false;
    }

    public record ControlObservation(
            boolean progressObserved, String progressDigest, List<RecoveryController.Update> recoveryUpdates) {}

    public record StallRecoverySignal(LoopDetectedException.Reason reason, String progressDigest, int attempt) {}
}
