package io.haifa.agent.testing.suite;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned host/provider combinations selected explicitly by an outer CI or local invocation. */
public record MatrixManifest(int schemaVersion, String matrixId, String strategy, List<Combination> combinations) {
    private static final Set<String> PLATFORMS = Set.of("linux", "macos", "windows");

    public MatrixManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("matrix schemaVersion must be 1");
        matrixId = require(matrixId, "matrixId");
        requireIdentifier(matrixId, "matrixId");
        strategy = require(strategy, "strategy");
        combinations = List.copyOf(Objects.requireNonNull(combinations, "combinations must not be null"));
        if (combinations.isEmpty()) throw new IllegalArgumentException("matrix combinations must not be empty");
        HashSet<String> identifiers = new HashSet<>();
        for (Combination combination : combinations) {
            if (!identifiers.add(combination.id())) {
                throw new IllegalArgumentException("duplicate matrix combination: " + combination.id());
            }
        }
    }

    public Combination requireCombination(String id) {
        String requested = require(id, "matrixCombination");
        return combinations.stream()
                .filter(combination -> combination.id().equals(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "matrix combination is unavailable in " + matrixId + ": " + requested));
    }

    public record Combination(
            String id, String platform, String modelProvider, String modelId, String webProvider, String mcpTarget) {
        public Combination {
            id = require(id, "combination.id");
            requireIdentifier(id, "combination.id");
            platform = require(platform, "combination.platform");
            if (!PLATFORMS.contains(platform)) {
                throw new IllegalArgumentException("unsupported matrix platform: " + platform);
            }
            modelProvider = require(modelProvider, "combination.modelProvider");
            modelId = require(modelId, "combination.modelId");
            webProvider = require(webProvider, "combination.webProvider");
            mcpTarget = require(mcpTarget, "combination.mcpTarget");
        }
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static void requireIdentifier(String value, String field) {
        if (!value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be lowercase kebab-case");
        }
    }
}
