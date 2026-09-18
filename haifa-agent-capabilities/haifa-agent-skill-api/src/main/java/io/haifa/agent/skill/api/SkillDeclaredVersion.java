package io.haifa.agent.skill.api;

public record SkillDeclaredVersion(String value) implements Comparable<SkillDeclaredVersion> {
    private static final String IDENTIFIER = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
    private static final String PATTERN = "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)"
            + "(?:-" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?";

    public SkillDeclaredVersion {
        value = SkillValues.text(value, "value", 128);
        if (!value.matches(PATTERN)) throw new IllegalArgumentException("invalid skill version");
    }

    @Override
    public int compareTo(SkillDeclaredVersion other) {
        return value.compareTo(other.value);
    }
}
