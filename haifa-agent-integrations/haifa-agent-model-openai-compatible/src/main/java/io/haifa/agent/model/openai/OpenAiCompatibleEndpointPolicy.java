package io.haifa.agent.model.openai;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

final class OpenAiCompatibleEndpointPolicy {
    private OpenAiCompatibleEndpointPolicy() {}

    static void validate(URI endpoint, boolean allowInsecureHttp, Set<String> allowedHosts, String requiredPath) {
        String scheme = endpoint.getScheme();
        boolean localTest = allowInsecureHttp && "http".equalsIgnoreCase(scheme);
        if (!"https".equalsIgnoreCase(scheme) && !localTest) {
            throw new IllegalArgumentException("provider endpoint must use HTTPS");
        }
        if (endpoint.getHost() == null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getRawUserInfo() != null) {
            throw new IllegalArgumentException("provider endpoint must be a clean absolute network URI");
        }
        String path = normalizedPath(endpoint);
        if (requiredPath != null && !requiredPath.equals(path)) {
            throw new IllegalArgumentException("provider endpoint path must be " + requiredPath);
        }
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        if (!localTest && !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("provider endpoint host is not allowed for this dialect");
        }
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
