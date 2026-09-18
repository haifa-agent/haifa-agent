package io.haifa.agent.mcp.config;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record McpProtocolProfile(String targetVersion) {
    public static final String VERSION_2025_03_26 = "2025-03-26";
    public static final String VERSION_2025_06_18 = "2025-06-18";
    public static final String VERSION_2025_11_25 = "2025-11-25";
    public static final String VERSION_2026_07_28 = "2026-07-28";
    private static final LocalDate LAST_ADAPTED_VERSION = LocalDate.parse(VERSION_2026_07_28);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)");
    private static final Set<String> SUPPORTED_VERSIONS =
            Set.of(VERSION_2025_03_26, VERSION_2025_06_18, VERSION_2025_11_25, VERSION_2026_07_28);

    public static final McpProtocolProfile FIXED_2025_03_26 = new McpProtocolProfile(VERSION_2025_03_26);
    public static final McpProtocolProfile FIXED_2025_06_18 = new McpProtocolProfile(VERSION_2025_06_18);
    public static final McpProtocolProfile FIXED_2025_11_25 = new McpProtocolProfile(VERSION_2025_11_25);
    public static final McpProtocolProfile FIXED_2026_07_28 = new McpProtocolProfile(VERSION_2026_07_28);

    public McpProtocolProfile {
        if (!SUPPORTED_VERSIONS.contains(targetVersion) && !isFutureVersion(targetVersion)) {
            throw new IllegalArgumentException("unsupported Haifa MCP protocol version: " + targetVersion);
        }
    }

    public boolean isModern() {
        return VERSION_2026_07_28.equals(targetVersion);
    }

    public boolean requiresAdaptation() {
        return isFutureVersion(targetVersion);
    }

    public static boolean isFutureVersion(String value) {
        if (value == null || !VERSION_PATTERN.matcher(value).matches()) return false;
        try {
            return LocalDate.parse(value).isAfter(LAST_ADAPTED_VERSION);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    public static Optional<String> findFutureVersion(CharSequence value) {
        if (value == null) return Optional.empty();
        var matcher = VERSION_PATTERN.matcher(value);
        String nearest = null;
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isFutureVersion(candidate) && (nearest == null || candidate.compareTo(nearest) < 0)) {
                nearest = candidate;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public static Optional<String> findFutureProtocolVersion(CharSequence value) {
        if (value == null || !value.toString().toLowerCase(Locale.ROOT).contains("protocol")) {
            return Optional.empty();
        }
        return findFutureVersion(value);
    }

    public static String adaptationNotice(String version) {
        if (!isFutureVersion(version)) throw new IllegalArgumentException("version is not a future MCP revision");
        return "MCP 协议版本" + version + "未适配，即将适配";
    }
}
