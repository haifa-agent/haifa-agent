package io.haifa.agent.execution.api;

import java.util.List;
import java.util.Objects;

public record ExecutionCommand(ExecutionCommandMode mode, List<String> argv) {
    public ExecutionCommand {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv must not be null"));
        if (argv.isEmpty()) throw new IllegalArgumentException("argv must not be empty");
        for (String argument : argv) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("argv contains null or NUL");
            }
        }
    }

    public String executable() {
        return argv.get(0);
    }
}
