package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.context.source.ContextSource;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded delivery state rebuilt on every iteration from authoritative product and Runtime facts. */
public final class CodingDeliveryContextSource implements ContextSource {
    public static final String SOURCE_ID = "coding.delivery.control";

    private final RunStateRepository runs;
    private final CodingTaskContractResolver contracts;
    private final CodingDeliveryEvidenceLedger evidence;
    private final CodingDeliveryProfile profile;

    public CodingDeliveryContextSource(
            RunStateRepository runs,
            CodingTaskContractResolver contracts,
            CodingDeliveryEvidenceLedger evidence,
            CodingDeliveryProfile profile) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.contracts = Objects.requireNonNull(contracts, "contracts must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String version() {
        return "2.0";
    }

    @Override
    public List<ContextItem> load(ContextBuildRequest request) {
        var run = runs.find(request.runId()).orElse(null);
        if (run == null) return List.of();
        CodingTaskContract contract = contracts.resolve(run);
        CodingDeliveryEvidenceLedger.Snapshot snapshot = evidence.reconstruct(run.id());
        long remainingModelCalls = Math.max(
                0, request.runBudget().maxModelCalls() - request.runUsage().modelCalls());
        long remainingToolCalls = Math.max(
                0, request.runBudget().maxToolCalls() - request.runUsage().toolCalls());
        long remainingWallTimeMillis = Math.max(
                0, run.limits().maxWallTimeMillis() - request.runUsage().wallTimeMillis());
        boolean reserve = (contract.intent() == CodingTaskIntent.CHANGE || contract.intent() == CodingTaskIntent.CREATE)
                && !snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)
                && (percent(remainingModelCalls, request.runBudget().maxModelCalls())
                                <= profile.modelCallsReservePercent()
                        || percent(remainingToolCalls, request.runBudget().maxToolCalls())
                                <= profile.toolCallsReservePercent()
                        || percent(remainingWallTimeMillis, run.limits().maxWallTimeMillis())
                                <= profile.wallTimeReservePercent());
        boolean workspaceChanged = snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE);
        boolean validationAttempted = snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT);
        String validationPassed = snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_PASSED)
                ? "true"
                : snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_FAILED) ? "false" : "unknown";
        boolean diffInspected = snapshot.has(CodingDeliveryEvidenceKind.DIFF_INSPECTION);
        boolean blockerConfirmed = snapshot.has(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED);
        String missingEvidence = String.join("|", missingEvidence(contract.intent(), snapshot));
        if (missingEvidence.isEmpty()) missingEvidence = "NONE";
        String text = String.join(
                "\n",
                "[CODING_RUN_STATE]",
                "remainingModelCalls=" + remainingModelCalls,
                "remainingToolCalls=" + remainingToolCalls,
                "remainingIterations=" + Math.max(0, run.limits().maxIterations() - request.iteration()),
                "remainingWallTimeSeconds=" + remainingWallTimeMillis / 1_000,
                "workspaceChanged=" + workspaceChanged,
                "validationAttempted=" + validationAttempted,
                "validationPassed=" + validationPassed,
                "diffInspected=" + diffInspected,
                "blockerConfirmed=" + blockerConfirmed,
                "deliveryReserve=" + (reserve ? "ACTIVE" : "INACTIVE"),
                "missingDeliveryEvidence=" + missingEvidence);
        String digest = digest(text);
        return List.of(new ContextItem(
                new ContextItemId("coding-delivery:" + run.id().value() + ":" + digest.substring(7, 23)),
                ContextItemType.RUNTIME_STATE,
                new TextContextContent(ContextRole.SYSTEM, text),
                Math.max(1, text.length() / 4),
                ContextPriority.CRITICAL,
                ContextRetention.MUST_KEEP,
                new ContextSecurity(Set.of("product-control", "safe-evidence"), true),
                new ContextProvenance("coding-product", run.id().value(), version(), digest),
                Map.of("evidenceDigest", digest, "deliveryReserve", Boolean.toString(reserve))));
    }

    private static List<String> missingEvidence(
            CodingTaskIntent intent, CodingDeliveryEvidenceLedger.Snapshot snapshot) {
        List<String> missing = new ArrayList<>();
        switch (intent) {
            case CHANGE, CREATE -> addChangeEvidence(snapshot, missing);
            case ANALYZE, REVIEW -> {
                if (!snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)) {
                    missing.add("READ_ONLY_EVIDENCE");
                }
            }
            case UNKNOWN -> {
                if (snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)) {
                    addChangeEvidence(snapshot, missing);
                } else if (!snapshot.has(CodingDeliveryEvidenceKind.READ_ONLY_INSPECTION)
                        && !(snapshot.has(CodingDeliveryEvidenceKind.BLOCKER_CONFIRMED)
                                && snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT))) {
                    missing.add("TASK_EVIDENCE");
                }
            }
        }
        return List.copyOf(missing);
    }

    private static void addChangeEvidence(CodingDeliveryEvidenceLedger.Snapshot snapshot, List<String> missing) {
        if (!snapshot.has(CodingDeliveryEvidenceKind.WORKSPACE_CHANGE)
                && !snapshot.has(CodingDeliveryEvidenceKind.NO_CHANGE_JUSTIFICATION)) {
            missing.add("WORKSPACE_CHANGE");
        }
        if (!snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_ATTEMPT)) {
            missing.add("VALIDATION_ATTEMPT");
        }
        if (!snapshot.has(CodingDeliveryEvidenceKind.DIFF_INSPECTION)) {
            missing.add("DIFF_INSPECTION");
        }
        if (snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_FAILED)
                && !snapshot.has(CodingDeliveryEvidenceKind.VALIDATION_PASSED)) {
            missing.add("VALIDATION_PASSED");
        }
    }

    private static int percent(long remaining, long maximum) {
        if (maximum <= 0) return 100;
        return (int) Math.max(0, Math.min(100, remaining * 100 / maximum));
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
