package io.haifa.agent.skill.api;

public record SkillContentDigest(String value) implements Comparable<SkillContentDigest> {
    public SkillContentDigest {
        value = SkillValues.text(value, "value", 71).toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("skill content digest must be a SHA-256 digest");
        }
    }

    @Override
    public int compareTo(SkillContentDigest other) {
        return value.compareTo(other.value);
    }
}
