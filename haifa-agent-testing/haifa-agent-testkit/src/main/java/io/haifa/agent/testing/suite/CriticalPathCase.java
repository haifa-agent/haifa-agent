package io.haifa.agent.testing.suite;

import java.util.List;
import java.util.Objects;

/** Stable public mapping from a critical-path case id to its executable Maven test selector. */
public record CriticalPathCase(
        String caseId,
        String title,
        TestScope scope,
        String module,
        String testSelector,
        boolean live,
        List<String> requiredSecrets) {
    public CriticalPathCase {
        caseId = require(caseId, "caseId");
        if (!caseId.matches("CP-[0-9]{2}")) {
            throw new IllegalArgumentException("caseId must match CP-NN");
        }
        title = require(title, "title");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        module = require(module, "module");
        if (!module.startsWith(":haifa-agent-")) {
            throw new IllegalArgumentException("module must be an internal artifact selector");
        }
        testSelector = require(testSelector, "testSelector");
        requiredSecrets = List.copyOf(Objects.requireNonNull(requiredSecrets, "requiredSecrets must not be null"));
        requiredSecrets.forEach(value -> require(value, "requiredSecret"));
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public enum TestScope {
        INTEGRATION,
        LIVE,
        E2E
    }
}
