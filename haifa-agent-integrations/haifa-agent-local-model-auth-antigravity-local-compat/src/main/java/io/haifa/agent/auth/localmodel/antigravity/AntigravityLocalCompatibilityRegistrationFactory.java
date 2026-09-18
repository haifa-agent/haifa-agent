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
        return create(environment::get);
    }

    public static Optional<AntigravityOAuthClientRegistration> create(
            java.util.function.Function<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (!"true".equalsIgnoreCase(trim(environment.apply("HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST")))) {
            return Optional.empty();
        }
        return Optional.of(createWithEnvironment(environment));
    }

    private static AntigravityOAuthClientRegistration createWithEnvironment(
            java.util.function.Function<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String reference = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_REFERENCE"))
                .orElse("google-antigravity-local-compat");
        String clientId = required(environment, "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID");
        String clientSecret = required(environment, "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET");
        String authEndpoint = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_AUTH_ENDPOINT"))
                .orElse(AntigravityOAuthClientRegistration.OFFICIAL_AUTHORIZATION_ENDPOINT.toString());
        String tokenEndpoint = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_TOKEN_ENDPOINT"))
                .orElse(AntigravityOAuthClientRegistration.OFFICIAL_TOKEN_ENDPOINT.toString());
        String userInfoEndpoint = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_USERINFO_ENDPOINT"))
                .orElse(AntigravityOAuthClientRegistration.OFFICIAL_USER_INFO_ENDPOINT.toString());
        String cloudCodeEndpoint = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_CLOUDCODE_ENDPOINT"))
                .orElse(AntigravityOAuthClientRegistration.OFFICIAL_CLOUDCODE_ENDPOINT.toString());
        String dailyCloudCodeEndpoint = Optional.ofNullable(
                        environment.apply("HAIFA_ANTIGRAVITY_DAILY_CLOUDCODE_ENDPOINT"))
                .orElse(AntigravityOAuthClientRegistration.OFFICIAL_DAILY_CLOUDCODE_ENDPOINT.toString());
        String redirectUri = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_REDIRECT_URI"))
                .orElse(AntigravityOAuthClientRegistration.OFFICIAL_REDIRECT_URI.toString());
        String userAgent = Optional.ofNullable(environment.apply("HAIFA_ANTIGRAVITY_USER_AGENT"))
                .orElse("Antigravity");
        boolean allowLoopback = "true"
                .equalsIgnoreCase(Optional.ofNullable(environment.apply("HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL"))
                        .orElse("false")
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
                "true".equalsIgnoreCase(trim(environment.apply("HAIFA_ANTIGRAVITY_ALLOW_ONBOARDING"))));
    }

    private static String required(java.util.function.Function<String, String> environment, String name) {
        String value = trim(environment.apply(name));
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required for local compatibility testing");
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
