package io.haifa.agent.auth.localmodel.codex;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Double-gated local-only compatibility registration. */
public final class CodexLocalCompatibilityRegistrationFactory {
    private CodexLocalCompatibilityRegistrationFactory() {}

    public static Optional<CodexOAuthClientRegistration> create(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        return create(environment::get);
    }

    public static Optional<CodexOAuthClientRegistration> create(
            java.util.function.Function<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (!"true".equalsIgnoreCase(trim(environment.apply("HAIFA_CODEX_LOCAL_COMPAT_TEST")))) {
            return Optional.empty();
        }
        String clientId = required(environment, "HAIFA_CODEX_OAUTH_CLIENT_ID");
        String originator = required(environment, "HAIFA_CODEX_ORIGINATOR");
        String redirect = Optional.ofNullable(environment.apply("HAIFA_CODEX_REDIRECT_URI"))
                .orElse("http://localhost:1455/auth/callback");
        String userAgent =
                Optional.ofNullable(environment.apply("HAIFA_CODEX_USER_AGENT")).orElse("haifa-agent-local-compat/1");
        return Optional.of(new CodexOAuthClientRegistration(
                "openai-codex-local-compat",
                clientId,
                CodexOAuthClientRegistration.OFFICIAL_AUTHORIZATION_ENDPOINT,
                CodexOAuthClientRegistration.OFFICIAL_TOKEN_ENDPOINT,
                URI.create(redirect),
                CodexOAuthClientRegistration.CODEX_API_ENDPOINT,
                originator,
                userAgent,
                true,
                false));
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
