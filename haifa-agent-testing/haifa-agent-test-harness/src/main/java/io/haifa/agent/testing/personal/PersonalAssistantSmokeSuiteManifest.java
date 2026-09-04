package io.haifa.agent.testing.personal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Private suite policy for the deterministic Personal Assistant smoke gate. */
public record PersonalAssistantSmokeSuiteManifest(
        int schemaVersion,
        String suiteType,
        String suiteId,
        String matrixRef,
        Budget budget,
        List<CaseSelection> cases) {
    public PersonalAssistantSmokeSuiteManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("suite schemaVersion must be 1");
        if (!"personal-assistant-smoke".equals(suiteType)) {
            throw new IllegalArgumentException("suiteType must be personal-assistant-smoke");
        }
        suiteId = text(suiteId, "suiteId");
        matrixRef = text(matrixRef, "matrixRef");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        if (cases.isEmpty()) throw new IllegalArgumentException("suite cases must not be empty");
        HashSet<String> identifiers = new HashSet<>();
        for (CaseSelection selection : cases) {
            if (!identifiers.add(selection.caseId())) {
                throw new IllegalArgumentException("duplicate suite case: " + selection.caseId());
            }
            PersonalAssistantSmokeCatalog.require(selection.caseId());
        }
    }

    public record Budget(int maxWallTimeMinutes, int maxParallelExternalCalls) {
        public Budget {
            if (maxWallTimeMinutes < 1 || maxWallTimeMinutes > 120) {
                throw new IllegalArgumentException("budget maxWallTimeMinutes is out of range");
            }
            if (maxParallelExternalCalls != 1) {
                throw new IllegalArgumentException("personal assistant smoke must run serially");
            }
        }
    }

    public record CaseSelection(String caseId, int repetitions, boolean blocking) {
        public CaseSelection {
            caseId = text(caseId, "caseId");
            if (repetitions < 1 || repetitions > 3) {
                throw new IllegalArgumentException("case repetitions is out of range");
            }
        }
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
