package io.haifa.agent.application.project.product.coding.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned product-owned base prompt for Haifa Coding Agent. */
public final class CodingAgentPrompt {
    public static final String VERSION = "1.4.0";
    public static final String RESOURCE = "/META-INF/haifa-agent/prompts/coding-agent-v1.txt";
    private static final Snapshot CURRENT = load();

    private CodingAgentPrompt() {}

    public static Snapshot current() {
        return CURRENT;
    }

    private static Snapshot load() {
        try (var input = CodingAgentPrompt.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Coding Agent prompt resource is unavailable");
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) throw new IllegalStateException("Coding Agent prompt resource is empty");
            return new Snapshot(VERSION, text, digest(text));
        } catch (IOException exception) {
            throw new IllegalStateException("Coding Agent prompt resource cannot be read", exception);
        }
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
