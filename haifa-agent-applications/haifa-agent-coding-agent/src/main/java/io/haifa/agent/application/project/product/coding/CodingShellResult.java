package io.haifa.agent.application.project.product.coding;

import java.util.Objects;
import java.util.Optional;

public record CodingShellResult(
        String status,
        Optional<Integer> exitCode,
        String safeSummary,
        Optional<String> outputReference,
        boolean truncated,
        boolean includedInContext) {
    public CodingShellResult {
        status = CodingProductValues.requireText(status, "status", 64);
        exitCode = Objects.requireNonNull(exitCode, "exitCode must not be null");
        safeSummary = CodingProductValues.requireText(safeSummary, "safeSummary", 16_384);
        outputReference = Objects.requireNonNull(outputReference, "outputReference must not be null");
    }
}
