package io.haifa.agent.testing.personal;

import java.util.Objects;

/** Stable public mapping from a Personal Assistant smoke case to its Maven test selector. */
public record PersonalAssistantSmokeCase(String caseId, String title, String module, String testSelector) {
    public PersonalAssistantSmokeCase {
        caseId = text(caseId, "caseId");
        if (!caseId.matches("PA-SM-[0-9]{2}")) {
            throw new IllegalArgumentException("caseId must match PA-SM-NN");
        }
        title = text(title, "title");
        module = text(module, "module");
        if (!module.startsWith(":haifa-agent-")) {
            throw new IllegalArgumentException("module must be an internal artifact selector");
        }
        testSelector = text(testSelector, "testSelector");
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
