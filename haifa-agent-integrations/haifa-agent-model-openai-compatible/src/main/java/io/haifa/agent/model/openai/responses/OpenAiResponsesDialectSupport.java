package io.haifa.agent.model.openai.responses;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class OpenAiResponsesDialectSupport {
    private OpenAiResponsesDialectSupport() {}

    static void validateEndpoint(URI endpoint, boolean allowInsecureHttp) {
        URI value = Objects.requireNonNull(endpoint, "endpoint must not be null");
        String host = value.getHost();
        if (host == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException("Responses endpoint must be a clean absolute network URI");
        }
        boolean loopback = isLoopback(value);
        if ("http".equalsIgnoreCase(value.getScheme())) {
            if (!allowInsecureHttp || !loopback) {
                throw new IllegalArgumentException("insecure Responses endpoint must be explicitly allowed loopback");
            }
        } else if (!"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("Responses endpoint must use HTTPS");
        }
    }

    static boolean isLoopback(URI endpoint) {
        String host = endpoint.getHost();
        return host != null
                && Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(host.toLowerCase(Locale.ROOT));
    }

    static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
