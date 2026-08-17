package io.haifa.agent.testing.delivery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Explicit host capabilities for Autonomous Delivery; platform fallback is never implicit. */
record DeliveryHostProfile(
        String id,
        String platform,
        String terminalBackend,
        String executionProvider,
        String networkPolicy,
        String shell,
        String isolationAssurance,
        String mavenWrapperName,
        boolean terminalDriverSupported) {

    static DeliveryHostProfile current(String id) {
        return require(id, System.getProperty("os.name", ""));
    }

    static DeliveryHostProfile require(String id, String osName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("--host-profile is required");
        }
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "trusted-host-default-v1" -> {
                String platform = platform(normalizedOs);
                boolean windows = platform.equals("windows");
                yield new DeliveryHostProfile(
                        id,
                        platform,
                        windows ? "conpty" : "unix-pty",
                        "host-guarded",
                        "allow",
                        "auto",
                        "TRUSTED_HOST_ONLY",
                        windows ? "mvnw.cmd" : "mvnw",
                        true);
            }
            case "posix-local-native-v1" -> {
                if (!(normalizedOs.contains("mac")
                        || normalizedOs.contains("linux")
                        || normalizedOs.contains("unix"))) {
                    throw new IllegalArgumentException("posix-local-native-v1 requires macOS or Linux");
                }
                yield new DeliveryHostProfile(
                        id,
                        normalizedOs.contains("mac") ? "macos" : "linux",
                        "unix-pty",
                        "local-native",
                        "deny",
                        "auto",
                        "LOCAL_NATIVE",
                        "mvnw",
                        true);
            }
            case "windows-host-trusted-v1" -> {
                if (!normalizedOs.contains("windows")) {
                    throw new IllegalArgumentException("windows-host-trusted-v1 requires Windows");
                }
                yield new DeliveryHostProfile(
                        id,
                        "windows",
                        "conpty",
                        "host-guarded",
                        "allow",
                        "powershell",
                        "TRUSTED_HOST_ONLY",
                        "mvnw.cmd",
                        true);
            }
            default -> throw new IllegalArgumentException("unknown Autonomous Delivery host profile: " + id);
        };
    }

    private static String platform(String normalizedOs) {
        if (normalizedOs.contains("windows")) return "windows";
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) return "macos";
        if (normalizedOs.contains("linux") || normalizedOs.contains("unix")) return "linux";
        throw new IllegalArgumentException("trusted-host-default-v1 requires macOS, Linux, or Windows");
    }

    Path requireMavenWrapper(Path projectRoot) throws IOException {
        Path wrapper = projectRoot.resolve(mavenWrapperName).toAbsolutePath().normalize();
        if (!Files.isRegularFile(wrapper)) {
            throw new IllegalArgumentException("missing Maven Wrapper for host profile: " + wrapper);
        }
        return wrapper.toRealPath();
    }
}
