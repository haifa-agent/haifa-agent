package io.haifa.agent.skill.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record SkillContent(
        FrozenSkillBinding binding, String instructions, Map<String, String> readableResources, int estimatedTokens) {
    public SkillContent {
        binding = Objects.requireNonNull(binding, "binding must not be null");
        instructions = SkillValues.text(instructions, "instructions", 262_144);
        Objects.requireNonNull(readableResources, "readableResources must not be null");
        if (readableResources.size() > 128) {
            throw new IllegalArgumentException("readableResources must contain at most 128 entries");
        }
        Map<String, String> copiedResources = new LinkedHashMap<>();
        long totalCharacters = 0;
        for (var entry : readableResources.entrySet()) {
            String path = SkillValues.text(entry.getKey(), "readableResources key", 512)
                    .replace('\\', '/');
            String content = Objects.requireNonNull(entry.getValue(), "readableResources value must not be null");
            if (content.length() > 524_288) {
                throw new IllegalArgumentException("readableResources value is too large");
            }
            totalCharacters += content.length();
            if (totalCharacters > 2_097_152) {
                throw new IllegalArgumentException("readableResources exceeds the total content limit");
            }
            if (copiedResources.putIfAbsent(path, content) != null) {
                throw new IllegalArgumentException("readableResources contains duplicate normalized paths");
            }
        }
        readableResources = Map.copyOf(copiedResources);
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
    }

    public String resource(String relativePath) {
        String normalized = SkillValues.text(relativePath, "relativePath", 512).replace('\\', '/');
        String content = readableResources.get(normalized);
        if (content == null) throw new IllegalArgumentException("skill resource is not indexed as readable text");
        return content;
    }
}
