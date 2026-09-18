package io.haifa.agent.application.project.product.coding;

import java.util.Objects;
import java.util.Optional;

public record CodingShellResult(
        String processState,
        Optional<Integer> exitCode,
        String safeSummary,
        Optional<String> outputReference,
        boolean truncated,
        boolean includedInContext) {
    public CodingShellResult {
        processState = CodingProductValues.requireText(processState, "processState", 64);
        exitCode = Objects.requireNonNull(exitCode, "exitCode must not be null");
        safeSummary = CodingProductValues.requireText(safeSummary, "safeSummary", 16_384);
        outputReference = Objects.requireNonNull(outputReference, "outputReference must not be null");
    }
}
