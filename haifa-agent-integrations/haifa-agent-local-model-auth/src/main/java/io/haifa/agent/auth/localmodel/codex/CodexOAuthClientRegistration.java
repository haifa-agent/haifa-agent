package io.haifa.agent.auth.localmodel.codex;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Trusted public OAuth client registration. No client id is compiled into the distribution. */
public record CodexOAuthClientRegistration(
        String reference,
        String clientId,
        URI authorizationEndpoint,
        URI tokenEndpoint,
        URI redirectUri,
        URI apiEndpoint,
        String originator,
        String userAgent,
        boolean unofficialLocalCompatibility,
        boolean allowLoopbackStub) {
    public static final URI OFFICIAL_AUTHORIZATION_ENDPOINT = URI.create("https://auth.openai.com/oauth/authorize");
    public static final URI OFFICIAL_TOKEN_ENDPOINT = URI.create("https://auth.openai.com/oauth/token");
    public static final URI CODEX_API_ENDPOINT = URI.create("https://chatgpt.com/backend-api/codex");

    public CodexOAuthClientRegistration {
        reference = text(reference, "reference");
        clientId = text(clientId, "clientId");
        authorizationEndpoint = cleanEndpoint(authorizationEndpoint, "authorizationEndpoint");
        tokenEndpoint = cleanEndpoint(tokenEndpoint, "tokenEndpoint");
        redirectUri = cleanEndpoint(redirectUri, "redirectUri");
        apiEndpoint = cleanEndpoint(apiEndpoint, "apiEndpoint");
        originator = text(originator, "originator");
        userAgent = text(userAgent, "userAgent");
        if (!reference.matches("[a-z0-9][a-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Codex client registration reference is invalid");
        }
        if (clientId.length() > 256 || containsHeaderSeparator(clientId)) {
            throw new IllegalArgumentException("Codex OAuth client id is invalid");
        }
        if (!originator.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Codex originator is invalid");
        }
        if (userAgent.length() > 128 || containsHeaderSeparator(userAgent)) {
            throw new IllegalArgumentException("Codex user agent is invalid");
        }
        boolean loopbackEndpoints =
                isLoopback(authorizationEndpoint) && isLoopback(tokenEndpoint) && isLoopback(apiEndpoint);
        if (allowLoopbackStub) {
            if (!loopbackEndpoints) throw new IllegalArgumentException("Codex OAuth stub endpoints must use loopback");
        } else if (!OFFICIAL_AUTHORIZATION_ENDPOINT.equals(authorizationEndpoint)
                || !OFFICIAL_TOKEN_ENDPOINT.equals(tokenEndpoint)
                || !CODEX_API_ENDPOINT.equals(apiEndpoint)) {
            throw new IllegalArgumentException("Codex OAuth endpoints are not approved");
        }
        if (!isLoopback(redirectUri)
                || !"http".equalsIgnoreCase(redirectUri.getScheme())
                || redirectUri.getPort() < 1
                || redirectUri.getPort() > 65_535
                || !"/auth/callback".equals(normalizedPath(redirectUri))) {
            throw new IllegalArgumentException("Codex OAuth redirect must be an exact loopback callback URI");
        }
    }

    public URI deviceUserCodeEndpoint() {
        return endpointAtAuthorizationOrigin("/api/accounts/deviceauth/usercode");
    }

    public URI deviceTokenEndpoint() {
        return endpointAtAuthorizationOrigin("/api/accounts/deviceauth/token");
    }

    public URI deviceVerificationUri() {
        return endpointAtAuthorizationOrigin("/codex/device");
    }

    public URI deviceRedirectUri() {
        return endpointAtAuthorizationOrigin("/deviceauth/callback");
    }

    private URI endpointAtAuthorizationOrigin(String path) {
        try {
            return new URI(
                    authorizationEndpoint.getScheme(),
                    null,
                    authorizationEndpoint.getHost(),
                    authorizationEndpoint.getPort(),
                    path,
                    null,
                    null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Approved Codex endpoint origin is invalid", exception);
        }
    }

    private static URI cleanEndpoint(URI value, String field) {
        URI normalized =
                Objects.requireNonNull(value, field + " must not be null").normalize();
        if (!normalized.isAbsolute()
                || normalized.getHost() == null
                || normalized.getRawUserInfo() != null
                || normalized.getRawQuery() != null
                || normalized.getRawFragment() != null) {
            throw new IllegalArgumentException(field + " must be a clean absolute network URI");
        }
        if (!Set.of("http", "https").contains(normalized.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(field + " scheme is unsupported");
        }
        String text = normalized.toString();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return URI.create(text);
    }

    private static boolean isLoopback(URI value) {
        return Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
                .contains(value.getHost().toLowerCase(Locale.ROOT));
    }

    private static String normalizedPath(URI value) {
        String path = value.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static boolean containsHeaderSeparator(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
