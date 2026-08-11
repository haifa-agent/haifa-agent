package io.haifa.agent.personalassistant.server.mission;

import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.web.DefaultWebUrlPolicy;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Deterministic canonicalization used before source identity and citation checks. */
final class ResearchSourceLocator {
    private static final Set<String> TRACKING_KEYS = Set.of("gclid", "fbclid", "mc_cid", "mc_eid");
    private static final DefaultWebUrlPolicy PUBLIC_WEB_URL_POLICY = new DefaultWebUrlPolicy();

    private ResearchSourceLocator() {}

    static Normalized normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) throw invalid();
        try {
            URI input = URI.create(value.trim()).normalize();
            var decision = PUBLIC_WEB_URL_POLICY.evaluate(input);
            if (!decision.allowed()) throw invalid();
            URI safe = decision.normalizedUrl();
            String scheme = safe.getScheme().toLowerCase(Locale.ROOT);
            String host = IDN.toASCII(safe.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.isBlank()) throw invalid();
            String path = safe.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            String query = normalizeQuery(safe.getRawQuery());
            int port = safe.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            URI normalized = new URI(scheme, null, host, port, path, query, null).normalize();
            String external = normalized.toASCIIString();
            return new Normalized(external, "sha256:" + sha256(external));
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", "Source locator is invalid", exception);
        }
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String normalized = java.util.Arrays.stream(query.split("&", -1))
                .filter(ResearchSourceLocator::notTracking)
                .collect(java.util.stream.Collectors.joining("&"));
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean notTracking(String parameter) {
        String key = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
        return !key.startsWith("utm_") && !TRACKING_KEYS.contains(key);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static MissionException invalid() {
        return new MissionException("MISSION_RESULT_SCHEMA_INVALID", "Source locator is invalid");
    }

    record Normalized(String locator, String digest) {}
}
