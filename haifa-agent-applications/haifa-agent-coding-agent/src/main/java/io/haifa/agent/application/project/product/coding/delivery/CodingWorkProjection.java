package io.haifa.agent.application.project.product.coding.delivery;

import java.util.List;
import java.util.Objects;

/** Bounded safe Coding projection rebuilt from persisted Runtime facts on every use. */
public record CodingWorkProjection(
        String schemaVersion,
        String taskContractDigest,
        CodingTaskIntent taskIntent,
        CodingWorkPhase phase,
        List<String> doneItemRefs,
        List<String> inProgressItemRefs,
        List<String> blockedItemRefs,
        List<String> readFileRefs,
        List<String> workspaceChangeRefs,
        List<String> validationEvidenceRefs,
        List<String> diffEvidenceRefs,
        List<String> failureClusterSummaries,
        String deliveryIntent,
        List<String> missingEvidence,
        int remainingModelCalls,
        int remainingToolCalls,
        int remainingPercent,
        boolean deliveryReserveActive,
        String digest) {
    public static final int MAXIMUM_REFERENCES_PER_KIND = 16;

    public CodingWorkProjection {
        schemaVersion = text(schemaVersion, "schemaVersion", 32);
        taskContractDigest = digest(taskContractDigest, "taskContractDigest");
        taskIntent = Objects.requireNonNull(taskIntent, "taskIntent must not be null");
        phase = Objects.requireNonNull(phase, "phase must not be null");
        doneItemRefs = references(doneItemRefs, "doneItemRefs");
        inProgressItemRefs = references(inProgressItemRefs, "inProgressItemRefs");
        blockedItemRefs = references(blockedItemRefs, "blockedItemRefs");
        readFileRefs = references(readFileRefs, "readFileRefs");
        workspaceChangeRefs = references(workspaceChangeRefs, "workspaceChangeRefs");
        validationEvidenceRefs = references(validationEvidenceRefs, "validationEvidenceRefs");
        diffEvidenceRefs = references(diffEvidenceRefs, "diffEvidenceRefs");
        failureClusterSummaries = references(failureClusterSummaries, "failureClusterSummaries");
        deliveryIntent = text(deliveryIntent, "deliveryIntent", 64);
        missingEvidence = references(missingEvidence, "missingEvidence");
        if (remainingModelCalls < 0 || remainingToolCalls < 0) {
            throw new IllegalArgumentException("remaining call budgets must not be negative");
        }
        if (remainingPercent < 0 || remainingPercent > 100) {
            throw new IllegalArgumentException("remainingPercent must be between zero and one hundred");
        }
        digest = digest(digest, "digest");
    }

    public String contextText() {
        return String.join(
                "\n",
                "[CODING_WORK_PROJECTION " + schemaVersion + "]",
                "taskContractDigest=" + taskContractDigest,
                "taskIntent=" + taskIntent,
                "phase=" + phase,
                "doneItemRefs=" + joined(doneItemRefs),
                "inProgressItemRefs=" + joined(inProgressItemRefs),
                "blockedItemRefs=" + joined(blockedItemRefs),
                "readFileRefs=" + joined(readFileRefs),
                "workspaceChangeRefs=" + joined(workspaceChangeRefs),
                "validationEvidenceRefs=" + joined(validationEvidenceRefs),
                "diffEvidenceRefs=" + joined(diffEvidenceRefs),
                "failureClusters=" + joined(failureClusterSummaries),
                "deliveryIntent=" + deliveryIntent,
                "missingEvidence=" + joined(missingEvidence),
                "remainingModelCalls=" + remainingModelCalls,
                "remainingToolCalls=" + remainingToolCalls,
                "remainingPercent=" + remainingPercent,
                "deliveryReserveActive=" + deliveryReserveActive,
                "deliveryReserveInstruction=" + (deliveryReserveActive ? "ONLY_MISSING_DELIVERY_EVIDENCE" : "NORMAL"),
                "projectionDigest=" + digest);
    }

    private static List<String> references(List<String> values, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (copy.size() > MAXIMUM_REFERENCES_PER_KIND
                || copy.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 192)) {
            throw new IllegalArgumentException(field + " contains an invalid bounded reference");
        }
        return copy;
    }

    private static String joined(List<String> values) {
        return values.isEmpty() ? "NONE" : String.join("|", values);
    }

    private static String text(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String digest(String value, String field) {
        String normalized = text(value, field, 71);
        if (!normalized.matches("(?:sha256:)?[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return normalized;
    }
}
