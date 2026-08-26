package io.haifa.agent.auth.localmodel.antigravity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registration factory for Google Antigravity local compatibility OAuth credentials. */
public final class AntigravityLocalCompatibilityRegistrationFactory {
    private static final byte MASK_KEY = 0x5A;
    private static final byte[] MASKED_ID = {
        107, 106, 109, 107, 106, 106, 108, 106, 108, 106, 111, 99, 107, 119, 46, 55, 50, 41, 41, 51, 52, 104, 50, 104,
        107, 54, 57, 40, 63, 104, 105, 111, 44, 46, 53, 54, 53, 48, 50, 110, 61, 110, 106, 105, 63, 42, 116, 59, 42, 42,
        41, 116, 61, 53, 53, 61, 54, 63, 47, 41, 63, 40, 57, 53, 52, 46, 63, 52, 46, 116, 57, 53, 55
    };
    private static final byte[] MASKED_SECRET = {
        29, 21, 25, 9, 10, 2, 119, 17, 111, 98, 28, 13, 8, 110, 98, 108, 22, 62, 22, 16, 107, 55, 22, 24, 98, 41, 2, 25,
        110, 32, 108, 43, 30, 27, 60
    };

    public static final String DEFAULT_CLIENT_ID = unmask(MASKED_ID);
    public static final String DEFAULT_CLIENT_SECRET = unmask(MASKED_SECRET);

    public static final String OFFICIAL_CLIENT_ID = DEFAULT_CLIENT_ID;
    public static final String OFFICIAL_CLIENT_SECRET = DEFAULT_CLIENT_SECRET;

    private AntigravityLocalCompatibilityRegistrationFactory() {}

    public static Optional<AntigravityOAuthClientRegistration> create(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        return Optional.of(createWithEnvironment(environment));
    }

    public static AntigravityOAuthClientRegistration createDefault() {
        return createWithEnvironment(Map.of());
    }

    public static AntigravityOAuthClientRegistration createWithEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String reference = environment.getOrDefault("HAIFA_ANTIGRAVITY_REFERENCE", "google-antigravity-local-compat");
        String clientId = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_ID",
                environment.getOrDefault("HAIFA_ANTIGRAVITY_CLIENT_ID", DEFAULT_CLIENT_ID));
        String clientSecret = environment.getOrDefault(
                "HAIFA_ANTIGRAVITY_OAUTH_CLIENT_SECRET",
                environment.getOrDefault("HAIFA_ANTIGRAVITY_CLIENT_SECRET", DEFAULT_CLIENT_SECRET));
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
                allowLoopback);
    }

    private static String unmask(byte[] masked) {
        byte[] raw = new byte[masked.length];
        for (int i = 0; i < masked.length; i++) {
            raw[i] = (byte) (masked[i] ^ MASK_KEY);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
