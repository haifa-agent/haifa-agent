package io.haifa.agent.application.project.product.coding.verification;

import java.util.List;
import java.util.Objects;

/** Bounded product-owned command candidates; it is a policy input, not a language plugin registry. */
public record CodingVerificationProfile(
        List<CodingVerificationCandidate> candidates, List<CodingVerificationCandidate> ignoredCandidates) {
    public static final int MAXIMUM_CANDIDATES = 16;

    public CodingVerificationProfile {
        candidates = bounded(candidates, "candidates");
        ignoredCandidates = bounded(ignoredCandidates, "ignoredCandidates");
    }

    public static CodingVerificationProfile empty() {
        return new CodingVerificationProfile(List.of(), List.of());
    }

    public String instructionText() {
        StringBuilder text = new StringBuilder(String.join(
                "\n",
                "[CODING_VERIFICATION_PROFILE]",
                "sourcePriority=USER_EXPLICIT>REPOSITORY_INSTRUCTIONS>BUILD_CONFIGURATION>ADJACENT_TEST>ECOSYSTEM_DEFAULT",
                "ladder=AFTER_EDIT>ADJACENT_CHANGE>MODULE_CHANGE>FINAL_GATE",
                "rule=run the narrowest risk-proportionate available candidate; retain every attempt",
                "rule=report selected, ignored, and discovered test counts only when tool evidence provides them",
                "rule=one selected test is never a complete test-suite claim"));
        if (candidates.isEmpty()) return text.append("\ncandidates=NONE").toString();
        for (int index = 0; index < candidates.size(); index++) {
            CodingVerificationCandidate candidate = candidates.get(index);
            text.append("\ncandidate.")
                    .append(index + 1)
                    .append("=")
                    .append(candidate.trigger())
                    .append('|')
                    .append(candidate.cost())
                    .append('|')
                    .append(candidate.timeout().toMillis())
                    .append("ms|")
                    .append(candidate.source())
                    .append('|')
                    .append(candidate.sourceReference())
                    .append('|')
                    .append(candidate.command());
        }
        return text.toString();
    }

    private static List<CodingVerificationCandidate> bounded(List<CodingVerificationCandidate> values, String field) {
        List<CodingVerificationCandidate> copy =
                List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (copy.size() > MAXIMUM_CANDIDATES) throw new IllegalArgumentException(field + " exceeds its bound");
        return copy;
    }
}
