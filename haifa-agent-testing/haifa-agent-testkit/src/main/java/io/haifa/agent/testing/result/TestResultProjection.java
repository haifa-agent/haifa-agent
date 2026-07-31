package io.haifa.agent.testing.result;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned cross-suite view that preserves each source system's native status alongside a common status. */
public record TestResultProjection(
        int schemaVersion,
        String suiteSystem,
        String suiteId,
        String caseId,
        String caseVersion,
        int repetition,
        String scope,
        String dependencyMode,
        String productEntry,
        String platform,
        String matrixCombination,
        String productCommit,
        String testConfigCommit,
        Status status,
        String nativeStatus,
        String startedAt,
        long durationMillis,
        Map<String, Object> providerUsage,
        String evidenceRef,
        String failureCategory) {
    public TestResultProjection {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported test result projection schema");
        }
        suiteSystem = requireText(suiteSystem, "suiteSystem");
        suiteId = requireText(suiteId, "suiteId");
        caseId = requireText(caseId, "caseId");
        caseVersion = requireText(caseVersion, "caseVersion");
        if (repetition < 1) {
            throw new IllegalArgumentException("repetition must be positive");
        }
        scope = requireText(scope, "scope");
        dependencyMode = requireText(dependencyMode, "dependencyMode");
        productEntry = requireText(productEntry, "productEntry");
        platform = requireText(platform, "platform");
        matrixCombination = requireText(matrixCombination, "matrixCombination");
        productCommit = requireText(productCommit, "productCommit");
        testConfigCommit = requireText(testConfigCommit, "testConfigCommit");
        status = Objects.requireNonNull(status, "status must not be null");
        nativeStatus = requireText(nativeStatus, "nativeStatus");
        if (startedAt != null && startedAt.isBlank()) {
            throw new IllegalArgumentException("startedAt must be null or non-blank");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        providerUsage = Map.copyOf(Objects.requireNonNull(providerUsage, "providerUsage must not be null"));
        evidenceRef = requireSafeRelativeRef(evidenceRef);
        failureCategory = requireText(failureCategory, "failureCategory");
    }

    private static String requireSafeRelativeRef(String value) {
        String ref = requireText(value, "evidenceRef").replace('\\', '/');
        if (ref.startsWith("/") || ref.matches("^[A-Za-z]:.*") || ref.contains("../") || ref.equals("..")) {
            throw new IllegalArgumentException("evidenceRef must be a safe relative reference");
        }
        return ref;
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    public enum Status {
        PASS,
        FAIL,
        BLOCKED_ENVIRONMENT,
        NOT_RUN,
        PLAN_ONLY,
        SKIPPED
    }

    public record Batch(
            int schemaVersion,
            String projectionType,
            String suiteSystem,
            String suiteId,
            String generatedAt,
            List<TestResultProjection> results) {
        public Batch {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("unsupported test result projection batch schema");
            }
            projectionType = requireText(projectionType, "projectionType");
            suiteSystem = requireText(suiteSystem, "suiteSystem");
            suiteId = requireText(suiteId, "suiteId");
            generatedAt = requireText(generatedAt, "generatedAt");
            results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
            for (TestResultProjection result : results) {
                if (!suiteSystem.equals(result.suiteSystem()) || !suiteId.equals(result.suiteId())) {
                    throw new IllegalArgumentException("batch and result suite identity must match");
                }
            }
        }
    }
}
