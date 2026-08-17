package io.haifa.agent.testing.delivery;

import io.haifa.agent.testing.fixtures.FixtureReference;
import java.util.List;

/** Private orchestration policy that references only stable public case identifiers. */
public record AutonomousDeliverySuiteManifest(
        int schemaVersion,
        String suiteId,
        String catalogRef,
        String phase,
        String matrixRef,
        FixtureReference fixture,
        String hiddenCaseSetRef,
        String readOnlyAnalyzeStubId,
        Budget budget,
        List<CaseSelection> cases) {
    public AutonomousDeliverySuiteManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported autonomous-delivery suite schema");
        }
        requireText(suiteId, "suiteId");
        if (!AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID.equals(catalogRef)) {
            throw new IllegalArgumentException("suite must reference the stable public catalog");
        }
        if (!List.of("PHASE_1", "PHASE_2", "PHASE_3").contains(phase)) {
            throw new IllegalArgumentException("suite phase is unsupported");
        }
        requireText(matrixRef, "matrixRef");
        fixture = fixture == null ? new FixtureReference("autonomous-delivery-v1", 1) : fixture;
        cases = List.copyOf(cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("suite cases must not be empty");
        }
    }

    public AutonomousDeliverySuiteManifest(
            int schemaVersion,
            String suiteId,
            String catalogRef,
            String phase,
            String matrixRef,
            String hiddenCaseSetRef,
            String readOnlyAnalyzeStubId,
            Budget budget,
            List<CaseSelection> cases) {
        this(
                schemaVersion,
                suiteId,
                catalogRef,
                phase,
                matrixRef,
                new FixtureReference("autonomous-delivery-v1", 1),
                hiddenCaseSetRef,
                readOnlyAnalyzeStubId,
                budget,
                cases);
    }

    public record Budget(
            long maxWallTimeMillis,
            int maxIterations,
            int maxToolCalls,
            int maxModelCalls,
            int maxParallelExternalCalls,
            long maxBatchWallTimeMillis,
            String costCurrency,
            long maxEstimatedCostMinorUnits,
            long inputCacheMissCostMinorUnitsPerMillionTokens,
            long outputCostMinorUnitsPerMillionTokens,
            long maxInputTokensPerRepeat,
            long maxOutputTokensPerRepeat) {
        public Budget(
                long maxWallTimeMillis,
                int maxIterations,
                int maxToolCalls,
                int maxModelCalls,
                int maxParallelExternalCalls) {
            this(
                    maxWallTimeMillis,
                    maxIterations,
                    maxToolCalls,
                    maxModelCalls,
                    maxParallelExternalCalls,
                    0,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0);
        }

        public Budget {
            if (maxWallTimeMillis <= 0
                    || maxIterations <= 0
                    || maxToolCalls <= 0
                    || maxModelCalls <= 0
                    || maxParallelExternalCalls != 1) {
                throw new IllegalArgumentException("suite budget is invalid");
            }
            boolean liveBudgetConfigured = costCurrency != null
                    || maxBatchWallTimeMillis != 0
                    || maxEstimatedCostMinorUnits != 0
                    || inputCacheMissCostMinorUnitsPerMillionTokens != 0
                    || outputCostMinorUnitsPerMillionTokens != 0
                    || maxInputTokensPerRepeat != 0
                    || maxOutputTokensPerRepeat != 0;
            if (liveBudgetConfigured
                    && (maxBatchWallTimeMillis <= 0
                            || costCurrency == null
                            || !costCurrency.matches("[A-Z]{3}")
                            || maxEstimatedCostMinorUnits <= 0
                            || inputCacheMissCostMinorUnitsPerMillionTokens <= 0
                            || outputCostMinorUnitsPerMillionTokens <= 0
                            || maxInputTokensPerRepeat <= 0
                            || maxOutputTokensPerRepeat <= 0)) {
                throw new IllegalArgumentException("live provider budget must be complete");
            }
        }

        boolean hasLiveProviderBudget() {
            return maxBatchWallTimeMillis > 0;
        }
    }

    public record CaseSelection(String caseId, int repetitions, boolean blocking) {
        public CaseSelection {
            requireText(caseId, "caseId");
            if (repetitions < 1 || repetitions > 5) {
                throw new IllegalArgumentException("case repetitions must be in [1, 5]");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
