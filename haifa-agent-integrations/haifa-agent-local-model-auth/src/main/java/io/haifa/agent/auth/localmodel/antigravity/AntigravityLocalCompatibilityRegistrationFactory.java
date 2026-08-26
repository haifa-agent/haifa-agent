package io.haifa.agent.auth.localmodel.antigravity;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registration factory for Google Antigravity local compatibility OAuth credentials. */
public final class AntigravityLocalCompatibilityRegistrationFactory {
    private AntigravityLocalCompatibilityRegistrationFactory() {}

    public static Optional<AntigravityOAuthClientRegistration> create(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (!"true".equalsIgnoreCase(trim(environment.get("HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST")))) {
            return Optional.empty();
        }
        return Optional.of(createWithEnvironment(environment));
    }

    private static AntigravityOAuthClientRegistration createWithEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String reference = environment.getOrDefault("HAIFA_ANTIGRAVITY_REFERENCE", "google-antigravity-local-compat");
        String clientId = required(environment, "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID");
        String clientSecret = required(environment, "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET");
        String authEndpoint = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_AUTH_ENDPOINT",
                AntigravityOAuthClientRegistration.OFFICIAL_AUTHORIZATION_ENDPOINT.toString());
        String tokenEndpoint = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_TOKEN_ENDPOINT",
                AntigravityOAuthClientRegistration.OFFICIAL_TOKEN_ENDPOINT.toString());
        String userInfoEndpoint = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_USERINFO_ENDPOINT",
                AntigravityOAuthClientRegistration.OFFICIAL_USER_INFO_ENDPOINT.toString());
        String cloudCodeEndpoint = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_CLOUDCODE_ENDPOINT",
                AntigravityOAuthClientRegistration.OFFICIAL_CLOUDCODE_ENDPOINT.toString());
        String dailyCloudCodeEndpoint = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_DAILY_CLOUDCODE_ENDPOINT",
                AntigravityOAuthClientRegistration.OFFICIAL_DAILY_CLOUDCODE_ENDPOINT.toString());
        String redirectUri = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_REDIRECT_URI", AntigravityOAuthClientRegistration.OFFICIAL_REDIRECT_URI.toString());
        String userAgent = environment.getOrDefault("HAIFA_ANTIGRAVITY_USER_AGENT", "Antigravity");
        boolean allowLoopback = "true"
                .equalsIgnoreCase(environment
                        .getOrDefault("HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL", "false")
                        .trim());

        return new AntigravityOAuthClientRegistration(
                reference,
                clientId,
                clientSecret,
                URI.create(authEndpoint),
                URI.create(tokenEndpoint),
                URI.create(userInfoEndpoint),
                URI.create(cloudCodeEndpoint),
                URI.create(dailyCloudCodeEndpoint),
                URI.create(redirectUri),
                AntigravityOAuthClientRegistration.DEFAULT_SCOPES,
                userAgent,
                true,
                allowLoopback,
                "true".equalsIgnoreCase(trim(environment.get("HAIFA_ANTIGRAVITY_ALLOW_ONBOARDING"))));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = trim(environment.get(name));
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required for local compatibility testing");
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
