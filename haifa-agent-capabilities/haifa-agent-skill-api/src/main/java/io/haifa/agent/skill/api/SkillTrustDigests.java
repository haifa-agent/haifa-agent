package io.haifa.agent.skill.api;

import io.haifa.agent.tool.api.ToolCoordinate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Canonical digests for the product-neutral trusted script execution envelope. */
public final class SkillTrustDigests {
    private SkillTrustDigests() {}

    public static String argumentPolicy(ToolCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate must not be null");
        return sha256(List.of(
                "fixed-business-tool-arguments-v1", coordinate.definitionHash().value()));
    }

    public static String executionProfile(String scriptRuntimeRef, List<String> executionProfileRefs) {
        String runtime = SkillValues.text(scriptRuntimeRef, "scriptRuntimeRef", 128);
        List<String> profiles =
                Objects.requireNonNull(executionProfileRefs, "executionProfileRefs must not be null").stream()
                        .map(value -> SkillValues.text(value, "executionProfileRef", 256))
                        .sorted()
                        .toList();
        return sha256(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("trusted-script-execution-profile-v1", runtime), profiles.stream())
                .toList());
    }

    public static String sandbox(String sandboxProfileRef) {
        return sha256(
                List.of("trusted-script-sandbox-v1", SkillValues.text(sandboxProfileRef, "sandboxProfileRef", 256)));
    }

    public static SkillContentDigest content(String content) {
        Objects.requireNonNull(content, "content must not be null");
        try {
            return new SkillContentDigest("sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256(List<String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = Objects.requireNonNull(field, "digest field must not be null");
            if (value.length() > 16_384) throw new IllegalArgumentException("digest field is too large");
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
