package io.haifa.agent.skill.api;

public record SkillSourceRef(String sourceId, String sourceVersion) implements Comparable<SkillSourceRef> {
    public SkillSourceRef {
        sourceId = SkillValues.text(sourceId, "sourceId", 128);
        sourceVersion = SkillValues.text(sourceVersion, "sourceVersion", 128);
    }

    public String externalForm() {
        return sourceId + "@" + sourceVersion;
    }

    @Override
    public int compareTo(SkillSourceRef other) {
        return externalForm().compareTo(other.externalForm());
    }
}
