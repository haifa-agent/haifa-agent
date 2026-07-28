package io.haifa.agent.sdk.internal;

import io.haifa.agent.sdk.product.ProductAssembly;
import io.haifa.agent.sdk.product.ProductAssemblyDiagnostic;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductCapabilityMode;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ResolvedProductContribution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ProductAssemblyResolver {
    public Resolution resolve(ProductProfile profile, List<ProductContribution> contributions) {
        Objects.requireNonNull(profile, "profile must not be null");
        List<ProductContribution> supplied =
                List.copyOf(Objects.requireNonNull(contributions, "contributions must not be null"));
        validateUniqueCoordinates(supplied);
        supplied.forEach(ProductContribution::validate);

        Map<ProductCapabilityId, List<ProductContribution>> byCapability = new TreeMap<>();
        supplied.forEach(contribution -> byCapability
                .computeIfAbsent(contribution.capabilityId(), ignored -> new ArrayList<>())
                .add(contribution));
        byCapability.values().forEach(values -> values.sort(Comparator.comparing(ProductContribution::coordinate)));

        Map<ProductCapabilityId, ProductContribution> selected = new LinkedHashMap<>();
        List<ProductAssemblyDiagnostic> diagnostics = new ArrayList<>();
        var allCapabilityIds = new java.util.TreeSet<ProductCapabilityId>();
        allCapabilityIds.addAll(profile.capabilityRequirements().keySet());
        allCapabilityIds.addAll(byCapability.keySet());
        for (ProductCapabilityId capabilityId : allCapabilityIds) {
            var requirement = profile.requirement(capabilityId);
            List<ProductContribution> candidates = byCapability.getOrDefault(capabilityId, List.of()).stream()
                    .filter(candidate -> requirement.allowedContributions().isEmpty()
                            || requirement.allowedContributions().contains(candidate.coordinate()))
                    .filter(candidate -> candidate.suitability().ordinal()
                            >= requirement.minimumSuitability().ordinal())
                    .toList();
            if (requirement.mode() == ProductCapabilityMode.NONE) {
                if (!byCapability.getOrDefault(capabilityId, List.of()).isEmpty()) {
                    throw failure("CAPABILITY_FORBIDDEN", capabilityId, "forbidden capability was contributed");
                }
                continue;
            }
            if (candidates.size() > 1) {
                throw failure("CAPABILITY_AMBIGUOUS", capabilityId, "multiple compatible contributions were supplied");
            }
            if (candidates.isEmpty()) {
                if (requirement.mode() == ProductCapabilityMode.REQUIRED) {
                    throw failure("CAPABILITY_REQUIRED", capabilityId, "required capability is unavailable");
                }
                diagnostics.add(new ProductAssemblyDiagnostic(
                        ProductAssemblyDiagnostic.Severity.WARNING,
                        "CAPABILITY_OPTIONAL_UNAVAILABLE",
                        capabilityId,
                        java.util.Optional.empty(),
                        "optional capability is unavailable"));
                continue;
            }
            selected.put(capabilityId, candidates.getFirst());
        }

        Map<ProductCapabilityId, ResolvedProductContribution> metadata = new LinkedHashMap<>();
        selected.forEach((id, contribution) -> metadata.put(id, ResolvedProductContribution.from(contribution)));
        String digest = assemblyDigest(profile, metadata);
        ProductAssembly assembly = new ProductAssembly(profile, digest, metadata, diagnostics);
        return new Resolution(assembly, selected);
    }

    private static void validateUniqueCoordinates(List<ProductContribution> contributions) {
        Map<ProductContributionCoordinate, ProductContribution> seen = new LinkedHashMap<>();
        for (ProductContribution contribution : contributions) {
            Objects.requireNonNull(contribution, "contribution must not be null");
            ProductContribution existing = seen.putIfAbsent(contribution.coordinate(), contribution);
            if (existing != null) {
                throw new ProductAssemblyException(
                        "CONTRIBUTION_COORDINATE_CONFLICT", "duplicate contribution coordinate was supplied");
            }
        }
    }

    private static ProductAssemblyException failure(String code, ProductCapabilityId id, String message) {
        return new ProductAssemblyException(code, message + ": " + id.value());
    }

    private static String assemblyDigest(
            ProductProfile profile, Map<ProductCapabilityId, ResolvedProductContribution> contributions) {
        StringBuilder canonical = new StringBuilder(profile.configurationDigest());
        new TreeMap<>(contributions).forEach((id, contribution) -> canonical
                .append('|')
                .append(id.value())
                .append(':')
                .append(contribution.coordinate().externalForm())
                .append(':')
                .append(contribution.configurationDigest())
                .append(':')
                .append(contribution.suitability()));
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record Resolution(ProductAssembly assembly, Map<ProductCapabilityId, ProductContribution> selected) {
        public Resolution {
            assembly = Objects.requireNonNull(assembly, "assembly must not be null");
            selected = Map.copyOf(Objects.requireNonNull(selected, "selected must not be null"));
        }
    }
}
