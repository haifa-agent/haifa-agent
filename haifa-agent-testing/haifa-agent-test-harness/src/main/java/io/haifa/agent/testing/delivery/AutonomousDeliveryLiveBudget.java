package io.haifa.agent.testing.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fail-closed authorization and conservative pricing evidence for one live phase batch. */
final class AutonomousDeliveryLiveBudget {
    private static final long MILLION = 1_000_000L;
    private static final long PROCESS_SETTLEMENT_ALLOWANCE_MILLIS = 120_000L;

    private AutonomousDeliveryLiveBudget() {}

    static Authorization authorize(AutonomousDeliverySuiteManifest suite, long approvedMaxCostMinorUnits) {
        Objects.requireNonNull(suite, "suite must not be null");
        AutonomousDeliverySuiteManifest.Budget budget = suite.budget();
        if (!budget.hasLiveProviderBudget()) {
            throw new IllegalArgumentException("suite has no frozen live provider budget");
        }
        if (approvedMaxCostMinorUnits <= 0) {
            throw new IllegalArgumentException("approved live provider cost must be positive");
        }
        if (budget.maxEstimatedCostMinorUnits() > approvedMaxCostMinorUnits) {
            throw new IllegalArgumentException("suite cost ceiling exceeds the explicitly approved limit");
        }
        long repetitions = suite.cases().stream()
                .mapToLong(AutonomousDeliverySuiteManifest.CaseSelection::repetitions)
                .sum();
        long worstCaseCost = Math.multiplyExact(repetitions, worstCaseRepeatCost(budget));
        if (worstCaseCost > budget.maxEstimatedCostMinorUnits()) {
            throw new IllegalArgumentException("suite cost ceiling is below the conservative token-price bound");
        }
        long boundedBatchTime = Math.multiplyExact(
                repetitions, Math.addExact(budget.maxWallTimeMillis(), PROCESS_SETTLEMENT_ALLOWANCE_MILLIS));
        if (boundedBatchTime > budget.maxBatchWallTimeMillis()) {
            throw new IllegalArgumentException("suite batch wall-time cannot contain all selected repetitions");
        }
        return new Authorization(
                budget.costCurrency(),
                approvedMaxCostMinorUnits,
                budget.maxEstimatedCostMinorUnits(),
                worstCaseCost,
                budget.maxBatchWallTimeMillis());
    }

    static Evidence evidence(
            AutonomousDeliverySuiteManifest suite,
            Authorization authorization,
            List<Map<String, Object>> results,
            long elapsedMillis) {
        Objects.requireNonNull(authorization, "authorization must not be null");
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
        long inputTokens = sum(results, "inputTokens");
        long outputTokens = sum(results, "outputTokens");
        long estimatedCost = Math.addExact(
                priced(inputTokens, suite.budget().inputCacheMissCostMinorUnitsPerMillionTokens()),
                priced(outputTokens, suite.budget().outputCostMinorUnitsPerMillionTokens()));
        boolean passed = inputTokens
                        <= Math.multiplyExact(results.size(), suite.budget().maxInputTokensPerRepeat())
                && outputTokens
                        <= Math.multiplyExact(results.size(), suite.budget().maxOutputTokensPerRepeat())
                && estimatedCost <= authorization.suiteCeilingMinorUnits()
                && estimatedCost <= authorization.approvedCeilingMinorUnits()
                && elapsedMillis <= authorization.maxBatchWallTimeMillis();
        return new Evidence(
                authorization.currency(),
                authorization.approvedCeilingMinorUnits(),
                authorization.suiteCeilingMinorUnits(),
                authorization.worstCaseCostMinorUnits(),
                estimatedCost,
                inputTokens,
                outputTokens,
                elapsedMillis,
                authorization.maxBatchWallTimeMillis(),
                passed);
    }

    private static long worstCaseRepeatCost(AutonomousDeliverySuiteManifest.Budget budget) {
        return Math.addExact(
                priced(budget.maxInputTokensPerRepeat(), budget.inputCacheMissCostMinorUnitsPerMillionTokens()),
                priced(budget.maxOutputTokensPerRepeat(), budget.outputCostMinorUnitsPerMillionTokens()));
    }

    private static long priced(long tokens, long ratePerMillion) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(tokens, ratePerMillion), MILLION - 1), MILLION);
    }

    private static long sum(List<Map<String, Object>> results, String key) {
        return results.stream()
                .mapToLong(value -> ((Number) value.get(key)).longValue())
                .sum();
    }

    record Authorization(
            String currency,
            long approvedCeilingMinorUnits,
            long suiteCeilingMinorUnits,
            long worstCaseCostMinorUnits,
            long maxBatchWallTimeMillis) {
        Map<String, Object> artifact() {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", 1);
            value.put("currency", currency);
            value.put("approvedCeilingMinorUnits", approvedCeilingMinorUnits);
            value.put("suiteCeilingMinorUnits", suiteCeilingMinorUnits);
            value.put("conservativeWorstCaseMinorUnits", worstCaseCostMinorUnits);
            value.put("maxBatchWallTimeMillis", maxBatchWallTimeMillis);
            value.put("providerReportedCostKnown", false);
            value.put("pricingMode", "FROZEN_CACHE_MISS_UPPER_BOUND");
            return Map.copyOf(value);
        }
    }

    record Evidence(
            String currency,
            long approvedCeilingMinorUnits,
            long suiteCeilingMinorUnits,
            long worstCaseCostMinorUnits,
            long estimatedCostMinorUnits,
            long inputTokens,
            long outputTokens,
            long elapsedMillis,
            long maxBatchWallTimeMillis,
            boolean passed) {
        Map<String, Object> artifact() {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", 1);
            value.put("currency", currency);
            value.put("approvedCeilingMinorUnits", approvedCeilingMinorUnits);
            value.put("suiteCeilingMinorUnits", suiteCeilingMinorUnits);
            value.put("conservativeWorstCaseMinorUnits", worstCaseCostMinorUnits);
            value.put("estimatedCostMinorUnits", estimatedCostMinorUnits);
            value.put("inputTokens", inputTokens);
            value.put("outputTokens", outputTokens);
            value.put("elapsedMillis", elapsedMillis);
            value.put("maxBatchWallTimeMillis", maxBatchWallTimeMillis);
            value.put("providerReportedCostKnown", false);
            value.put("pricingMode", "FROZEN_CACHE_MISS_UPPER_BOUND");
            value.put("passed", passed);
            return Map.copyOf(value);
        }
    }
}
