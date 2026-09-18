package io.haifa.agent.store.sqlite.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record SqliteMigration(long version, String name, String script, String checksum, List<String> statements) {
    public SqliteMigration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be positive");
        }
        name = requireText(name, "name");
        script = requireText(script, "script");
        checksum = requireText(checksum, "checksum");
        statements = List.copyOf(Objects.requireNonNull(statements, "statements must not be null"));
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("migration statements must not be empty");
        }
    }

    public static SqliteMigration fromScript(long version, String name, String script) {
        Objects.requireNonNull(script, "script must not be null");
        return new SqliteMigration(version, name, script, sha256(script), SqlScriptParser.parse(script));
    }

    private static String sha256(String script) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
