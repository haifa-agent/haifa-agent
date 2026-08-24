package io.haifa.agent.model.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Strict, non-logging projection of a Codex OAuth access token into request headers. */
final class OpenAiCodexAuthentication {
    private static final String AUTH_CLAIM = "https://api.openai.com/auth";

    private OpenAiCodexAuthentication() {}

    static void apply(
            HttpRequest.Builder builder, ObjectMapper json, ResolvedModelSnapshot snapshot, String accessToken) {
        String accountId = accountId(json, accessToken);
        builder.header("Authorization", "Bearer " + accessToken)
                .header("chatgpt-account-id", accountId)
                .header("originator", option(snapshot, OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION))
                .header("User-Agent", option(snapshot, OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION));
    }

    private static String accountId(ObjectMapper json, String accessToken) {
        try {
            String[] parts = accessToken.split("\\.", -1);
            if (parts.length != 3 || parts[1].isEmpty()) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(padded(parts[1]));
            JsonNode root = json.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode claim = root.path(AUTH_CLAIM).path("chatgpt_account_id");
            if (!claim.isTextual()) throw new IllegalArgumentException();
            String accountId = claim.textValue().trim();
            if (!accountId.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException();
            return accountId;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Codex access token does not contain a valid account identity");
        }
    }

    private static String padded(String value) {
        return switch (value.length() % 4) {
            case 0 -> value;
            case 2 -> value + "==";
            case 3 -> value + "=";
            default -> throw new IllegalArgumentException();
        };
    }

    private static String option(ResolvedModelSnapshot snapshot, String name) {
        return (String) snapshot.providerOptions().get(name);
    }
}
