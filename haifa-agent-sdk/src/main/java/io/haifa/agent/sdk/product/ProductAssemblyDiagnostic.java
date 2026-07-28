package io.haifa.agent.sdk.product;

import java.util.Objects;
import java.util.Optional;

public record ProductAssemblyDiagnostic(
        Severity severity,
        String code,
        ProductCapabilityId capabilityId,
        Optional<ProductContributionCoordinate> coordinate,
        String safeMessage) {

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public ProductAssemblyDiagnostic {
        severity = Objects.requireNonNull(severity, "severity must not be null");
        code = ProductValues.text(code, "code", 128);
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        safeMessage = ProductValues.text(safeMessage, "safeMessage", 512);
    }
}
