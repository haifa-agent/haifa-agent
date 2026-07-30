package io.haifa.agent.application.project.product.coding.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Content-addressed, non-executable verification plan owned by the Coding product. */
public record CodingVerificationPlan(
        String planId,
        String taskContractRef,
        Set<CodingVerificationDimension> dimensions,
        Set<String> requiredEvidence,
        CodingVerificationRiskLevel riskLevel,
        Instant createdAt,
        String digest) {
    private static final int MAX_DIMENSIONS = 9;
    private static final int MAX_EVIDENCE = 18;

    public CodingVerificationPlan {
        planId = text(planId, "planId", 320);
        taskContractRef = digest(taskContractRef, "taskContractRef");
        dimensions = Set.copyOf(Objects.requireNonNull(dimensions, "dimensions must not be null"));
        requiredEvidence = Set.copyOf(Objects.requireNonNull(requiredEvidence, "requiredEvidence must not be null"));
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (dimensions.isEmpty() || dimensions.size() > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("dimensions must contain between 1 and " + MAX_DIMENSIONS + " values");
        }
        if (requiredEvidence.isEmpty() || requiredEvidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "requiredEvidence must contain between 1 and " + MAX_EVIDENCE + " values");
        }
        requiredEvidence.forEach(value -> text(value, "requiredEvidence", 96));
        String expected = canonicalDigest(planId, taskContractRef, dimensions, requiredEvidence, riskLevel, createdAt);
        digest = digest(digest, "digest");
        if (!expected.equals(digest)) {
            throw new IllegalArgumentException("digest does not match frozen verification plan");
        }
    }

    public static CodingVerificationPlan freeze(
            String planId,
            String taskContractRef,
            Set<CodingVerificationDimension> dimensions,
            Set<String> requiredEvidence,
            CodingVerificationRiskLevel riskLevel,
            Instant createdAt) {
        return new CodingVerificationPlan(
                planId,
                taskContractRef,
                dimensions,
                requiredEvidence,
                riskLevel,
                createdAt,
                canonicalDigest(planId, taskContractRef, dimensions, requiredEvidence, riskLevel, createdAt));
    }

    private static String canonicalDigest(
            String planId,
            String taskContractRef,
            Set<CodingVerificationDimension> dimensions,
            Set<String> requiredEvidence,
            CodingVerificationRiskLevel riskLevel,
            Instant createdAt) {
        String canonical = String.join(
                "\n",
                text(planId, "planId", 320),
                digest(taskContractRef, "taskContractRef"),
                dimensions.stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .map(Enum::name)
                        .reduce((left, right) -> left + "|" + right)
                        .orElseThrow(),
                requiredEvidence.stream()
                        .sorted()
                        .reduce((left, right) -> left + "|" + right)
                        .orElseThrow(),
                Objects.requireNonNull(riskLevel, "riskLevel must not be null").name(),
                Objects.requireNonNull(createdAt, "createdAt must not be null").toString());
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String digest(String value, String field) {
        String result = text(value, field, 71);
        if (!result.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 reference");
        }
        return result;
    }

    private static String text(String value, String field, int maximumLength) {
        String result =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (result.isEmpty() || result.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return result;
    }
}
