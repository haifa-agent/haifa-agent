package io.haifa.agent.model.openai.responses;

import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpRequest;
import java.util.Objects;

/** Strict, non-logging projection of a Codex OAuth access token and account identity into request headers. */
final class OpenAiCodexAuthentication {
    private OpenAiCodexAuthentication() {}

    static void apply(
            HttpRequest.Builder builder,
            ResolvedModelSnapshot snapshot,
            String accessToken,
            CodexAccountIdentity identity) {
        Objects.requireNonNull(builder, "builder must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        String safeToken = validateHeaderValue(accessToken, "access token");
        String safeAccount = validateHeaderValue(
                Objects.requireNonNull(identity, "identity must not be null").accountId(), "account identity");
        String originator = option(snapshot, OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION);
        String userAgent = option(snapshot, OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION);

        builder.header("Authorization", "Bearer " + safeToken)
                .header("chatgpt-account-id", safeAccount)
                .header("originator", validateHeaderValue(originator, "originator"))
                .header("User-Agent", validateHeaderValue(userAgent, "User-Agent"));
    }

    private static String option(ResolvedModelSnapshot snapshot, String name) {
        Object value = snapshot.providerOptions().get(name);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException("snapshot is missing required Codex option: " + name);
        }
        return stringValue;
    }

    static String validateHeaderValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " contains invalid control characters");
        }
        return value.trim();
    }
}
