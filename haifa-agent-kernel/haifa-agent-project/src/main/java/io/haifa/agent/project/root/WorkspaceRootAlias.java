package io.haifa.agent.project.root;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies a root directory alias in a multi-root workspace session.
 */
public record WorkspaceRootAlias(String value) {
    public static final String MAIN_VALUE = "main";
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");
    public static final WorkspaceRootAlias MAIN = new WorkspaceRootAlias(MAIN_VALUE);

    public WorkspaceRootAlias {
        Objects.requireNonNull(value, "value must not be null");
        String trimmed = value.trim();
        if (!ALIAS_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("root alias must match pattern ^[a-zA-Z0-9_-]{1,32}$, got: " + value);
        }
        value = trimmed;
    }

    public static WorkspaceRootAlias of(String value) {
        if (value != null && value.trim().equals(MAIN_VALUE)) {
            return MAIN;
        }
        return new WorkspaceRootAlias(value);
    }

    public boolean isMain() {
        return MAIN_VALUE.equals(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
