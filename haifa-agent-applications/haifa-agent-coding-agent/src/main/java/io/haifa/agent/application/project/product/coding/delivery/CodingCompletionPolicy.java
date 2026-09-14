package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.completion.CompletionBlocker;
import io.haifa.agent.runtime.core.completion.CompletionPolicy;
import io.haifa.agent.runtime.core.completion.CompletionPolicyResult;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal Coding completion gate over trusted task mode and reconstructed authoritative evidence.
 * Validation blockers require the explicit, frozen {@code requiresValidationEvidence} fact from the
 * session's verification configuration; observed workspace changes alone never demand Build/Test.
 */
public final class CodingCompletionPolicy implements CompletionPolicy {
    private final CodingTaskModeResolver taskModes;
    private final CodingDeliveryEvidenceLedger evidence;
    private final CodingVerificationProfileProvider verificationProfiles;

    public CodingCompletionPolicy(
            CodingTaskModeResolver taskModes,
            CodingDeliveryEvidenceLedger evidence,
            CodingVerificationProfileProvider verificationProfiles) {
        this.taskModes = Objects.requireNonNull(taskModes, "taskModes must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.verificationProfiles =
                Objects.requireNonNull(verificationProfiles, "verificationProfiles must not be null");
    }

    @Override
    public CompletionPolicyResult evaluate(AgentRun run, FinalAnswerDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        return evaluateEvidence(run);
    }

    public CompletionPolicyResult evaluateEvidence(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        CodingTaskIntent taskMode = taskModes.resolve(run);
        CodingDeliveryEvidenceLedger.Snapshot snapshot = evidence.reconstruct(run.id());
        List<CompletionBlocker> blockers = new ArrayList<>();
        switch (taskMode) {
            case CHANGE, CREATE -> changeBlockers(run, snapshot, blockers);
            case ANALYZE -> readOnlyBlockers(snapshot, blockers, "ANALYSIS_EVIDENCE_MISSING");
            case REVIEW -> readOnlyBlockers(snapshot, blockers, "REVIEW_EVIDENCE_MISSING");
            case UNKNOWN -> unknownBlockers(run, snapshot, blockers);
        }
        if (blockers.isEmpty()) return CompletionPolicyResult.accepted(snapshot.codes());
        return CompletionPolicyResult.blocked(blockers, snapshot.codes());
    }

    private void changeBlockers(
            AgentRun run, CodingDeliveryEvidenceLedger.Snapshot snapshot, List<CompletionBlocker> blockers) {
        if (!snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)
                && !snapshot.has(CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION)) {
            blockers.add(CompletionBlocker.recoverable(
                    "WORKSPACE_CHANGE_MISSING",
                    "No authoritative workspace change or evidence-backed no-change result exists.",
                    "WORKSPACE_CHANGE"));
        }
        if (validationRequired(run)) {
            boolean changed = snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE);
            if (!snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)
                    || (changed
                            && !snapshot.hasAfter(
                                    CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT,
                                    CodingDeliveryEvidenceKind.WORKSPACE_CHANGE))) {
                blockers.add(CompletionBlocker.recoverable(
                        "VALIDATION_ATTEMPT_MISSING",
                        changed
                                ? "No authoritative validation attempt exists after the latest workspace change."
                                : "No authoritative validation attempt exists.",
                        "VALIDATION_ATTEMPT"));
            }
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

    private boolean validationRequired(AgentRun run) {
        return verificationProfiles.configurationFor(run.id()).requiresValidationEvidence();
    }

    private void unknownBlockers(
            AgentRun run, CodingDeliveryEvidenceLedger.Snapshot snapshot, List<CompletionBlocker> blockers) {
        if (snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)) {
            changeBlockers(run, snapshot, blockers);
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
