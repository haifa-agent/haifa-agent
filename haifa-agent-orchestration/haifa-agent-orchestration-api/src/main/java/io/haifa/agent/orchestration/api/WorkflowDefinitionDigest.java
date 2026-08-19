package io.haifa.agent.orchestration.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record WorkflowDefinitionDigest(String value) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public WorkflowDefinitionDigest {
        value = Objects.requireNonNull(value, "value must not be null");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("workflow definition digest must be lowercase SHA-256");
        }
    }
}
