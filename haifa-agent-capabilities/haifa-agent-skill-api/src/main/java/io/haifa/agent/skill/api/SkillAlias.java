package io.haifa.agent.skill.api;

public record SkillAlias(String value) implements Comparable<SkillAlias> {
    public SkillAlias {
        value = new SkillName(value).value();
    }

    @Override
    public int compareTo(SkillAlias other) {
        return value.compareTo(other.value);
    }
}
