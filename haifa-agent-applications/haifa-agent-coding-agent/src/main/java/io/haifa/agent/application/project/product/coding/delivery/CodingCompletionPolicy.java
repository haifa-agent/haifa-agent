package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.completion.CompletionBlocker;
import io.haifa.agent.runtime.core.completion.CompletionPolicy;
import io.haifa.agent.runtime.core.completion.CompletionPolicyResult;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minimal Coding completion gate over trusted task mode and reconstructed authoritative evidence. */
public final class CodingCompletionPolicy implements CompletionPolicy {
    private final CodingTaskModeResolver taskModes;
    private final CodingDeliveryEvidenceLedger evidence;
    private final CodingDeliveryProfile profile;

    public CodingCompletionPolicy(
            CodingTaskModeResolver taskModes, CodingDeliveryEvidenceLedger evidence, CodingDeliveryProfile profile) {
        this.taskModes = Objects.requireNonNull(taskModes, "taskModes must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
    }

    @Override
    public CompletionPolicyResult evaluate(AgentRun run, FinalAnswerDecision decision) {
        CodingTaskIntent taskMode = taskModes.resolve(run);
        CodingDeliveryEvidenceLedger.Snapshot snapshot = evidence.reconstruct(run.id());
        List<CompletionBlocker> blockers = new ArrayList<>();
        switch (taskMode) {
            case CHANGE, CREATE -> changeBlockers(snapshot, blockers);
            case ANALYZE -> readOnlyBlockers(snapshot, blockers, "ANALYSIS_EVIDENCE_MISSING");
            case REVIEW -> readOnlyBlockers(snapshot, blockers, "REVIEW_EVIDENCE_MISSING");
            case UNKNOWN -> unknownBlockers(snapshot, blockers);
        }
        if (blockers.isEmpty()) return CompletionPolicyResult.accepted(snapshot.codes());
        return CompletionPolicyResult.blocked(blockers, snapshot.codes());
    }

    private void changeBlockers(CodingDeliveryEvidenceLedger.Snapshot snapshot, List<CompletionBlocker> blockers) {
        if (!snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)
                && !snapshot.has(CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION)) {
            blockers.add(CompletionBlocker.recoverable(
                    "WORKSPACE_CHANGE_MISSING",
                    "No authoritative workspace change or evidence-backed no-change result exists.",
                    "WORKSPACE_CHANGE"));
        }
        if (!snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)) {
            blockers.add(CompletionBlocker.recoverable(
                    "VALIDATION_ATTEMPT_MISSING", "No authoritative validation attempt exists.", "VALIDATION_ATTEMPT"));
        }
        if (!snapshot.has(CodingDeliveryEvidenceKind.DIFF_INSPECTION)) {
            blockers.add(CompletionBlocker.recoverable(
                    "DIFF_INSPECTION_MISSING", "No completed structured diff inspection exists.", "DIFF_INSPECTION"));
        }
        if (snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_FAILED)
                && !snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_PASSED)
                && !(profile.allowBlockedValidation() && snapshot.has(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED))) {
            blockers.add(CompletionBlocker.recoverable(
                    "VALIDATION_NOT_PASSED",
                    "Validation did not pass and the frozen profile does not permit blocked completion.",
                    "VALIDATION_PASSED"));
        }
    }

    private static void readOnlyBlockers(
            CodingDeliveryEvidenceLedger.Snapshot snapshot, List<CompletionBlocker> blockers, String missingCode) {
        if (snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)) {
            blockers.add(CompletionBlocker.recoverable(
                    "READ_ONLY_INTENT_HAS_CHANGES",
                    "A read-only task unexpectedly changed the workspace.",
                    "INTENT_CONFIRMATION"));
        }
        if (!snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)) {
            blockers.add(CompletionBlocker.recoverable(
                    missingCode, "No authoritative read-only evidence was inspected.", "READ_ONLY_EVIDENCE"));
        }
    }

    private void unknownBlockers(CodingDeliveryEvidenceLedger.Snapshot snapshot, List<CompletionBlocker> blockers) {
        if (snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)) {
            changeBlockers(snapshot, blockers);
            return;
        }
        if (snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)) return;
        if (snapshot.has(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED)
                && snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)) {
            return;
        }
        // An untrusted interactive prompt may be conversational. With no authoritative workspace
        // activity to enforce, a text-only assistant response completes the turn normally. Trusted
        // task modes and observed workspace changes retain their delivery evidence requirements.
    }
}
