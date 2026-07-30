package io.haifa.agent.application.project.product.coding.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Content-addressed Coding product contract reconstructed from authoritative Run input. */
public record CodingTaskContract(
        String taskId,
        CodingTaskIntent intent,
        CodingTaskIntentSource intentSource,
        int confidencePercent,
        Set<String> acceptanceCriteriaRefs,
        Set<CodingDeliveryRequirement> deliveryRequirements,
        Instant createdAt,
        String contractDigest) {
    public CodingTaskContract {
        taskId = text(taskId, "taskId", 256);
        intent = Objects.requireNonNull(intent, "intent must not be null");
        intentSource = Objects.requireNonNull(intentSource, "intentSource must not be null");
        if (confidencePercent < 0 || confidencePercent > 100) {
            throw new IllegalArgumentException("confidencePercent must be between zero and one hundred");
        }
        acceptanceCriteriaRefs =
                Set.copyOf(Objects.requireNonNull(acceptanceCriteriaRefs, "acceptanceCriteriaRefs must not be null"));
        deliveryRequirements =
                Set.copyOf(Objects.requireNonNull(deliveryRequirements, "deliveryRequirements must not be null"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        String expected = digest(
                taskId,
                intent,
                intentSource,
                confidencePercent,
                acceptanceCriteriaRefs,
                deliveryRequirements,
                createdAt);
        contractDigest = text(contractDigest, "contractDigest", 128);
        if (!expected.equals(contractDigest)) {
            throw new IllegalArgumentException("contractDigest does not match frozen contract content");
        }
    }

    public static CodingTaskContract freeze(
            String taskId,
            CodingTaskIntent intent,
            CodingTaskIntentSource source,
            int confidencePercent,
            Set<String> acceptanceCriteriaRefs,
            Set<CodingDeliveryRequirement> requirements,
            Instant createdAt) {
        return new CodingTaskContract(
                taskId,
                intent,
                source,
                confidencePercent,
                acceptanceCriteriaRefs,
                requirements,
                createdAt,
                digest(taskId, intent, source, confidencePercent, acceptanceCriteriaRefs, requirements, createdAt));
    }

    private static String digest(
            String taskId,
            CodingTaskIntent intent,
            CodingTaskIntentSource source,
            int confidencePercent,
            Set<String> criteria,
            Set<CodingDeliveryRequirement> requirements,
            Instant createdAt) {
        String canonical = String.join(
                "\n",
                text(taskId, "taskId", 256),
                intent.name(),
                source.name(),
                Integer.toString(confidencePercent),
                criteria.stream()
                        .sorted()
                        .reduce((left, right) -> left + "|" + right)
                        .orElse(""),
                requirements.stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .map(Enum::name)
                        .reduce((left, right) -> left + "|" + right)
                        .orElse(""),
                createdAt.toString());
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String text(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return normalized;
    }
}
