package io.haifa.agent.skill.api;

public record SkillName(String value) implements Comparable<SkillName> {
    public SkillName {
        value = SkillValues.text(value, "value", 64);
        if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("invalid skill name");
        }
    }

    @Override
    public int compareTo(SkillName other) {
        return value.compareTo(other.value);
    }
}
