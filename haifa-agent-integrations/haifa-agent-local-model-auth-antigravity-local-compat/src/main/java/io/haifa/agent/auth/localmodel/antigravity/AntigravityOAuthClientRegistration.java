package io.haifa.agent.auth.localmodel.antigravity;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Trusted public OAuth client registration for Google Antigravity. Secret is never leaked to logs or toString. */
public record AntigravityOAuthClientRegistration(
        String reference,
        String clientId,
        String clientSecret,
        URI authorizationEndpoint,
        URI tokenEndpoint,
        URI userInfoEndpoint,
        URI cloudCodeEndpoint,
        URI dailyCloudCodeEndpoint,
        URI redirectUri,
        List<String> scopes,
        String userAgent,
        boolean unofficialLocalCompatibility,
        boolean allowLoopbackStub,
        boolean allowOnboarding) {

    public static final URI OFFICIAL_AUTHORIZATION_ENDPOINT =
            URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    public static final URI OFFICIAL_TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    public static final URI OFFICIAL_USER_INFO_ENDPOINT = URI.create("https://www.googleapis.com/oauth2/v2/userinfo");
    public static final URI OFFICIAL_CLOUDCODE_ENDPOINT = URI.create("https://cloudcode-pa.googleapis.com");
    public static final URI OFFICIAL_DAILY_CLOUDCODE_ENDPOINT = URI.create("https://daily-cloudcode-pa.googleapis.com");
    public static final URI OFFICIAL_REDIRECT_URI = URI.create("http://localhost:51121/oauth-callback");

    public static final List<String> DEFAULT_SCOPES = List.of(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs");

    public AntigravityOAuthClientRegistration {
        reference = text(reference, "reference");
        clientId = text(clientId, "clientId");
        clientSecret = text(clientSecret, "clientSecret");
        authorizationEndpoint = cleanEndpoint(authorizationEndpoint, "authorizationEndpoint");
        tokenEndpoint = cleanEndpoint(tokenEndpoint, "tokenEndpoint");
        userInfoEndpoint = cleanEndpoint(userInfoEndpoint, "userInfoEndpoint");
        cloudCodeEndpoint = cleanEndpoint(cloudCodeEndpoint, "cloudCodeEndpoint");
        dailyCloudCodeEndpoint = cleanEndpoint(dailyCloudCodeEndpoint, "dailyCloudCodeEndpoint");
        redirectUri = cleanEndpoint(redirectUri, "redirectUri");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
        userAgent = text(userAgent, "userAgent");

        if (!reference.matches("[a-z0-9][a-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Antigravity client registration reference is invalid");
        }
        if (clientId.length() > 256 || containsHeaderSeparator(clientId)) {
            throw new IllegalArgumentException("Antigravity OAuth client id is invalid");
        }
        if (clientSecret.length() > 256 || containsHeaderSeparator(clientSecret)) {
            throw new IllegalArgumentException("Antigravity OAuth client secret is invalid");
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("Antigravity scopes must not be empty");
        }
        if (userAgent.length() > 128 || containsHeaderSeparator(userAgent)) {
            throw new IllegalArgumentException("Antigravity user agent is invalid");
        }

        boolean loopbackEndpoints = isLoopback(authorizationEndpoint)
                && isLoopback(tokenEndpoint)
                && isLoopback(userInfoEndpoint)
                && isLoopback(cloudCodeEndpoint)
                && isLoopback(dailyCloudCodeEndpoint);

        if (allowLoopbackStub) {
            if (!loopbackEndpoints) {
                throw new IllegalArgumentException("Antigravity OAuth stub endpoints must use loopback");
            }
        } else if (!OFFICIAL_AUTHORIZATION_ENDPOINT.equals(authorizationEndpoint)
                || !OFFICIAL_TOKEN_ENDPOINT.equals(tokenEndpoint)
                || !OFFICIAL_USER_INFO_ENDPOINT.equals(userInfoEndpoint)
                || !OFFICIAL_CLOUDCODE_ENDPOINT.equals(cloudCodeEndpoint)
                || !OFFICIAL_DAILY_CLOUDCODE_ENDPOINT.equals(dailyCloudCodeEndpoint)) {
            throw new IllegalArgumentException("Antigravity OAuth endpoints are not approved");
        }

        if (!isLoopback(redirectUri)
                || !"http".equalsIgnoreCase(redirectUri.getScheme())
                || redirectUri.getPort() < 1
                || redirectUri.getPort() > 65_535
                || !"/oauth-callback".equals(normalizedPath(redirectUri))) {
            throw new IllegalArgumentException(
                    "Antigravity OAuth redirect must be an exact loopback callback URI (/oauth-callback)");
        }
    }

    @Override
    public String toString() {
        return "AntigravityOAuthClientRegistration[reference=" + reference + ", clientId=" + clientId
                + ", clientSecret=<redacted>, authorizationEndpoint=" + authorizationEndpoint
                + ", tokenEndpoint=" + tokenEndpoint + ", userInfoEndpoint=" + userInfoEndpoint
                + ", cloudCodeEndpoint=" + cloudCodeEndpoint + ", dailyCloudCodeEndpoint="
                + dailyCloudCodeEndpoint + ", redirectUri=" + redirectUri + ", scopes=" + scopes
                + ", userAgent=" + userAgent + ", unofficialLocalCompatibility=" + unofficialLocalCompatibility
                + ", allowLoopbackStub=" + allowLoopbackStub + "]";
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
