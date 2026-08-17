package io.haifa.agent.testing.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral platform matrix with versioned suite-specific extensions. */
public record PlatformManifest(
        int schemaVersion, String matrixId, String strategy, List<PlatformProfile> combinations) {
    public PlatformManifest {
        if (schemaVersion != 2) throw new IllegalArgumentException("platform manifest schemaVersion must be 2");
        matrixId = identifier(matrixId, "matrixId");
        strategy = require(strategy, "strategy");
        combinations = List.copyOf(Objects.requireNonNull(combinations, "combinations must not be null"));
        if (combinations.isEmpty()) throw new IllegalArgumentException("platform combinations must not be empty");
        HashSet<String> identifiers = new HashSet<>();
        for (PlatformProfile combination : combinations) {
            if (!identifiers.add(combination.id())) {
                throw new IllegalArgumentException("duplicate platform combination: " + combination.id());
            }
        }
    }

    public PlatformProfile requireCombination(String id) {
        String requested = identifier(id, "platform combination");
        return combinations.stream()
                .filter(combination -> combination.id().equals(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "platform combination is unavailable in " + matrixId + ": " + requested));
    }

    public record PlatformProfile(String id, String platform, Map<String, Object> extensions) {
        private static final java.util.Set<String> PLATFORMS = java.util.Set.of("linux", "macos", "windows");

        public PlatformProfile {
            id = identifier(id, "combination.id");
            platform = require(platform, "combination.platform");
            if (!PLATFORMS.contains(platform)) {
                throw new IllegalArgumentException("unsupported platform: " + platform);
            }
            extensions = Map.copyOf(Objects.requireNonNullElse(extensions, Map.of()));
        }

        public PlatformProfile(String id, String platform) {
            this(id, platform, Map.of());
        }

        public PlatformProfile(
                String id,
                String platform,
                String terminalBackend,
                String sandboxProfile,
                String networkPolicy,
                String shell,
                String isolationAssurance,
                String hostProfile,
                int maxParallelExternalCalls) {
            this(
                    id,
                    platform,
                    Map.of(
                            "terminalBackend", terminalBackend,
                            "sandboxProfile", sandboxProfile,
                            "networkPolicy", networkPolicy,
                            "shell", shell,
                            "isolationAssurance", isolationAssurance,
                            "hostProfile", hostProfile,
                            "maxParallelExternalCalls", maxParallelExternalCalls));
        }

        public String terminalBackend() {
            return requireString("terminalBackend");
        }

        public String sandboxProfile() {
            return requireString("sandboxProfile");
        }

        public String networkPolicy() {
            return requireString("networkPolicy");
        }

        public String shell() {
            return requireString("shell");
        }

        public String isolationAssurance() {
            return requireString("isolationAssurance");
        }

        public String hostProfile() {
            return requireString("hostProfile");
        }

        public int maxParallelExternalCalls() {
            return requireInt("maxParallelExternalCalls");
        }

        public String requireString(String name) {
            Object value = extensions.get(name);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("platform extension is required: " + name);
            }
            return text;
        }

        public int requireInt(String name) {
            Object value = extensions.get(name);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("numeric platform extension is required: " + name);
            }
            return number.intValue();
        }

        public void requireCurrentHost() {
            String current = currentPlatform(System.getProperty("os.name", ""));
            if (!platform.equals(current)) {
                throw new IllegalArgumentException(
                        "platform combination " + id + " targets " + platform + " but current host is " + current);
            }
        }
    }

    public static String currentPlatform(String osName) {
        String normalized = osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) return "windows";
        if (normalized.contains("mac") || normalized.contains("darwin")) return "macos";
        if (normalized.contains("linux") || normalized.contains("unix")) return "linux";
        throw new IllegalArgumentException("unsupported host platform: " + normalized);
    }

    static String identifier(String value, String field) {
        String normalized = require(value, field);
        if (!normalized.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be lowercase kebab-case");
        }
        return normalized;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
