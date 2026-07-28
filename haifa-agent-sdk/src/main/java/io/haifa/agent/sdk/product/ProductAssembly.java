package io.haifa.agent.sdk.product;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProductAssembly(
        ProductProfile profile,
        String assemblyDigest,
        Map<ProductCapabilityId, ResolvedProductContribution> contributions,
        List<ProductAssemblyDiagnostic> diagnostics) {

    public ProductAssembly {
        profile = Objects.requireNonNull(profile, "profile must not be null");
        assemblyDigest = ProductValues.requireDigest(assemblyDigest, "assemblyDigest");
        contributions = Map.copyOf(Objects.requireNonNull(contributions, "contributions must not be null"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }
}
