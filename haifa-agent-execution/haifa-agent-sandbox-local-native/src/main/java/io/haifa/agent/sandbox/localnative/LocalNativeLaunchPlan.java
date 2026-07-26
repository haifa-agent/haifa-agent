package io.haifa.agent.sandbox.localnative;

import java.util.List;
import java.util.Objects;

record LocalNativeLaunchPlan(List<String> argv) {
    LocalNativeLaunchPlan {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv must not be null"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("argv is invalid");
        }
    }
}
