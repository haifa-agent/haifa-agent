package io.haifa.agent.testing.suite;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Private-repository suite selection. It references public cases but never redefines their implementation. */
public record SuiteManifest(
        int schemaVersion, String suiteId, String matrixRef, Budget budget, List<CaseSelection> cases) {
    public SuiteManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("suite schemaVersion must be 1");
        suiteId = require(suiteId, "suiteId");
        matrixRef = require(matrixRef, "matrixRef");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        if (cases.isEmpty()) throw new IllegalArgumentException("suite cases must not be empty");
        HashSet<String> identifiers = new HashSet<>();
        for (CaseSelection selection : cases) {
            if (!identifiers.add(selection.caseId())) {
                throw new IllegalArgumentException("duplicate suite case: " + selection.caseId());
            }
            CriticalPathCatalog.require(selection.caseId());
        }
    }

    public record Budget(int maxWallTimeMinutes, double maxEstimatedCostUsd, int maxParallelExternalCalls) {
        public Budget {
            if (maxWallTimeMinutes < 1 || maxWallTimeMinutes > 720) {
                throw new IllegalArgumentException("budget maxWallTimeMinutes is out of range");
            }
            if (!Double.isFinite(maxEstimatedCostUsd) || maxEstimatedCostUsd <= 0 || maxEstimatedCostUsd > 10_000) {
                throw new IllegalArgumentException("budget maxEstimatedCostUsd is out of range");
            }
            if (maxParallelExternalCalls < 1 || maxParallelExternalCalls > 8) {
                throw new IllegalArgumentException("budget maxParallelExternalCalls is out of range");
            }
        }
    }

    public record CaseSelection(String caseId, int repetitions, boolean blocking) {
        public CaseSelection {
            caseId = require(caseId, "caseId");
            if (repetitions < 1 || repetitions > 10) {
                throw new IllegalArgumentException("case repetitions is out of range");
            }
        }
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
