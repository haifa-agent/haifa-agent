package io.haifa.agent.skill.core;

import io.haifa.agent.skill.api.SkillContentDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class SkillDigests {
    private SkillDigests() {}

    static SkillContentDigest sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static SkillContentDigest sha256(byte[] value) {
        try {
            return new SkillContentDigest("sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
