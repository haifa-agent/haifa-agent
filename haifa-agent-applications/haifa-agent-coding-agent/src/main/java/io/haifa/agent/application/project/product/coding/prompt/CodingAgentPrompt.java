package io.haifa.agent.application.project.product.coding.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Versioned product-owned base prompt for Haifa Coding Agent. */
public final class CodingAgentPrompt {
    public static final String VERSION = "1.6.0";
    public static final String RESOURCE = "/META-INF/haifa-agent/prompts/coding-agent-v1.txt";
    private static final String WORKSPACE_ATTACHMENT_ALIAS = "workspace_attach";
    private static final String ATTACHMENT_GUIDANCE = """
            - This run discloses workspace_attach. When the user asks to read or edit a directory outside the current workspace, request workspace_attach with the exact absolute directory and the least permission needed. Wait for the user's approval result; never guess host paths or access the directory before it is authorized. Use host absolute paths for every file operation in every authorized directory; relative paths and root aliases are invalid. A previously persisted absolute path is not authorization, so always rely on the current tool result and current workspace scope.""";
    private static final String NO_ATTACHMENT_GUIDANCE = """
            - This run does not expose a workspace attachment tool. Do not ask the user to authorize or attach another directory. For a target outside the current workspace, state the scope limitation and continue only with already authorized workspace paths; never guess host paths.""";
    private static final Snapshot CURRENT = load();

    private CodingAgentPrompt() {}

    public static Snapshot current() {
        return CURRENT;
    }

    /** Renders instructions for the exact tool aliases frozen into one Coding Agent run. */
    public static Snapshot forDisclosedToolAliases(Set<String> toolAliases) {
        Objects.requireNonNull(toolAliases, "toolAliases must not be null");
        String guidance = toolAliases.contains(WORKSPACE_ATTACHMENT_ALIAS)
                ? ATTACHMENT_GUIDANCE
                : NO_ATTACHMENT_GUIDANCE;
        return snapshot(CURRENT.text() + "\n\n" + guidance);
    }

    private static Snapshot load() {
        try (var input = CodingAgentPrompt.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Coding Agent prompt resource is unavailable");
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) throw new IllegalStateException("Coding Agent prompt resource is empty");
            return snapshot(text);
        } catch (IOException exception) {
            throw new IllegalStateException("Coding Agent prompt resource cannot be read", exception);
        }
    }

    private static Snapshot snapshot(String text) {
        return new Snapshot(VERSION, text, digest(text));
    }

    private static String digest(String value) {
        try {
            byte[] result = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Snapshot(String version, String text, String digest) {
        public Snapshot {
            version = requireText(version, "version");
            text = requireText(text, "text");
            digest = requireText(digest, "digest");
        }

        public String identity() {
            return "coding-agent-prompt@" + version + "#" + digest;
        }

        private static String requireText(String value, String field) {
            String normalized =
                    Objects.requireNonNull(value, field + " must not be null").trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
            return normalized;
        }
    }
}
