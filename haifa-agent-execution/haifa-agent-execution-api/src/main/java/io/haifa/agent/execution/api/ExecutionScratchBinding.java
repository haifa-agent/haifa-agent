package io.haifa.agent.execution.api;

import java.util.Objects;

/** Binds one trusted environment name to a logical child of the execution scratch root. */
public record ExecutionScratchBinding(String environmentName, String relativeDirectory) {
    public ExecutionScratchBinding {
        environmentName = ExecutionScratchSpaceSpec.requireEnvironmentName(environmentName);
        relativeDirectory = requireRelativeDirectory(relativeDirectory);
    }

    private static String requireRelativeDirectory(String value) {
        String normalized = Objects.requireNonNull(value, "relativeDirectory must not be null")
                .trim();
        if (normalized.isEmpty()
                || normalized.length() > 256
                || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\\') >= 0
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("relativeDirectory is invalid");
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")
                    || !segment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("relativeDirectory contains an unsafe segment");
            }
        }
        return normalized;
    }
}
