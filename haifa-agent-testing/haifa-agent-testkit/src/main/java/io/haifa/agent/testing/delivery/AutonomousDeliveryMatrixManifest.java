package io.haifa.agent.testing.delivery;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Versioned platform and host-execution bindings for Autonomous Delivery campaigns. */
public record AutonomousDeliveryMatrixManifest(
        int schemaVersion, String matrixId, String strategy, List<Combination> combinations) {

    public AutonomousDeliveryMatrixManifest {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Autonomous Delivery matrix schemaVersion must be 2");
        }
        matrixId = identifier(matrixId, "matrixId");
        strategy = require(strategy, "strategy");
        if (!"explicit".equals(strategy)) {
            throw new IllegalArgumentException("Autonomous Delivery matrix strategy must be explicit");
        }
        combinations = List.copyOf(Objects.requireNonNull(combinations, "combinations must not be null"));
        if (combinations.isEmpty()) {
            throw new IllegalArgumentException("Autonomous Delivery matrix combinations must not be empty");
        }
        HashSet<String> identifiers = new HashSet<>();
        for (Combination combination : combinations) {
            if (!identifiers.add(combination.id())) {
                throw new IllegalArgumentException("duplicate Autonomous Delivery combination: " + combination.id());
            }
        }
    }

    public Combination requireCombination(String combinationId) {
        if (combinationId == null || combinationId.isBlank()) {
            throw new IllegalArgumentException("--matrix-combination is required");
        }
        return combinations.stream()
                .filter(combination -> combination.id().equals(combinationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown Autonomous Delivery matrix combination: " + combinationId));
    }

    public record Combination(
            String id,
            String platform,
            String terminalBackend,
            String sandboxProfile,
            String networkPolicy,
            String shell,
            String isolationAssurance,
            String hostProfile,
            int maxParallelExternalCalls) {

        public Combination {
            id = identifier(id, "combination.id");
            platform = require(platform, "combination.platform");
            terminalBackend = identifier(terminalBackend, "combination.terminalBackend");
            sandboxProfile = identifier(sandboxProfile, "combination.sandboxProfile");
            networkPolicy = identifier(networkPolicy, "combination.networkPolicy");
            shell = identifier(shell, "combination.shell");
            isolationAssurance = require(isolationAssurance, "combination.isolationAssurance");
            hostProfile = identifier(hostProfile, "combination.hostProfile");
            if (maxParallelExternalCalls != 1) {
                throw new IllegalArgumentException("Autonomous Delivery combinations must serialize external calls");
            }
            validatePlatformContract(
                    platform, terminalBackend, sandboxProfile, networkPolicy, shell, isolationAssurance, hostProfile);
        }

        DeliveryHostProfile requireCurrentHost() {
            return requireHost(System.getProperty("os.name", ""));
        }

        DeliveryHostProfile requireHost(String osName) {
            String currentPlatform = AutonomousDeliveryMatrixManifest.platform(osName);
            if (!platform.equals(currentPlatform)) {
                throw new IllegalArgumentException("matrix combination "
                        + id
                        + " targets "
                        + platform
                        + " but current host is "
                        + currentPlatform);
            }
            DeliveryHostProfile profile = DeliveryHostProfile.require(hostProfile, osName);
            if (!profile.platform().equals(platform)
                    || !profile.terminalBackend().equals(terminalBackend)
                    || !profile.executionProvider().equals(sandboxProfile)
                    || !profile.networkPolicy().equals(networkPolicy)
                    || !profile.shell().equals(shell)
                    || !profile.isolationAssurance().equals(isolationAssurance)) {
                throw new IllegalArgumentException("matrix combination does not match its DeliveryHostProfile: " + id);
            }
            return profile;
        }

        private static void validatePlatformContract(
                String platform,
                String terminalBackend,
                String sandboxProfile,
                String networkPolicy,
                String shell,
                String isolationAssurance,
                String hostProfile) {
            switch (hostProfile) {
                case "trusted-host-default-v1" -> {
                    switch (platform) {
                        case "windows" -> requireEquals(terminalBackend, "conpty", "Windows terminalBackend");
                        case "macos", "linux" -> requireEquals(terminalBackend, "unix-pty", "POSIX terminalBackend");
                        default ->
                            throw new IllegalArgumentException("unsupported Autonomous Delivery platform: " + platform);
                    }
                    requireEquals(sandboxProfile, "host-guarded", "trusted Host sandboxProfile");
                    requireEquals(networkPolicy, "allow", "trusted Host networkPolicy");
                    requireEquals(shell, "auto", "trusted Host shell");
                    requireEquals(isolationAssurance, "TRUSTED_HOST_ONLY", "trusted Host isolationAssurance");
                }
                case "windows-host-trusted-v1" -> {
                    requireEquals(platform, "windows", "Windows Host profile platform");
                    requireEquals(terminalBackend, "conpty", "Windows terminalBackend");
                    requireEquals(sandboxProfile, "host-guarded", "Windows sandboxProfile");
                    requireEquals(networkPolicy, "allow", "Windows networkPolicy");
                    requireEquals(shell, "powershell", "Windows shell");
                    requireEquals(isolationAssurance, "TRUSTED_HOST_ONLY", "Windows isolationAssurance");
                }
                case "posix-local-native-v1" -> {
                    if (!(platform.equals("macos") || platform.equals("linux"))) {
                        throw new IllegalArgumentException("POSIX Local Native profile requires macOS or Linux");
                    }
                    requireEquals(terminalBackend, "unix-pty", "POSIX terminalBackend");
                    requireEquals(sandboxProfile, "local-native", "POSIX sandboxProfile");
                    requireEquals(networkPolicy, "deny", "POSIX networkPolicy");
                    requireEquals(shell, "auto", "POSIX shell");
                    requireEquals(isolationAssurance, "LOCAL_NATIVE", "POSIX isolationAssurance");
                }
                default ->
                    throw new IllegalArgumentException("unsupported Autonomous Delivery hostProfile: " + hostProfile);
            }
        }
    }

    private static String platform(String osName) {
        String normalized = osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("windows")) return "windows";
        if (normalized.contains("mac") || normalized.contains("darwin")) return "macos";
        if (normalized.contains("linux") || normalized.contains("unix")) return "linux";
        throw new IllegalArgumentException("unsupported host platform: " + normalized);
    }

    private static void requireEquals(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static String identifier(String value, String field) {
        String normalized = require(value, field);
        if (!normalized.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be lowercase kebab-case");
        }
        return normalized;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
