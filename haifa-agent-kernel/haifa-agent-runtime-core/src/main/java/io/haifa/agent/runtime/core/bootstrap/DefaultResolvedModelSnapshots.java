package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;

/** Compatibility defaults for the first configured external model. */
public final class DefaultResolvedModelSnapshots {
    private DefaultResolvedModelSnapshots() {}

    public static ResolvedModelSnapshot deepSeekV4Pro() {
        return new ResolvedModelSnapshot(
                new ModelProviderId("deepseek"),
                new ModelDefinitionId("deepseek-v4-pro"),
                "deepseek-v4-pro",
                "openai-compatible",
                "1.0.0",
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT),
                Map.of("thinking", "disabled", "maxOutputTokens", 8192),
                sha256(
                        "deepseek|deepseek-v4-pro|openai-compatible|env://DEEPSEEK_API_KEY|thinking=disabled|maxOutputTokens=8192"));
    }

    private static String sha256(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
