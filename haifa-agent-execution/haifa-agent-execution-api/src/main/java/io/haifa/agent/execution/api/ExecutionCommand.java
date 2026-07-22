package io.haifa.agent.execution.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A process command whose DIRECT argv and SHELL text have intentionally distinct semantics. */
public final class ExecutionCommand {
    private static final int MAX_SHELL_COMMAND_LENGTH = 32 * 1024;

    private final ExecutionCommandMode mode;
    private final List<String> argv;
    private final String shellCommand;

    public ExecutionCommand(ExecutionCommandMode mode, List<String> argv) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        if (mode != ExecutionCommandMode.DIRECT) {
            throw new IllegalArgumentException("use ExecutionCommand.shell for shell commands");
        }
        this.argv = validateArgv(argv);
        this.shellCommand = null;
    }

    private ExecutionCommand(String shellCommand) {
        mode = ExecutionCommandMode.SHELL;
        argv = List.of();
        this.shellCommand = validateShellCommand(shellCommand);
    }

    public static ExecutionCommand direct(List<String> argv) {
        return new ExecutionCommand(ExecutionCommandMode.DIRECT, argv);
    }

    public static ExecutionCommand shell(String command) {
        return new ExecutionCommand(command);
    }

    public ExecutionCommandMode mode() {
        return mode;
    }

    public List<String> argv() {
        if (mode != ExecutionCommandMode.DIRECT) {
            throw new IllegalStateException("shell commands do not expose argv");
        }
        return argv;
    }

    public Optional<String> optionalShellCommand() {
        return Optional.ofNullable(shellCommand);
    }

    public String shellCommand() {
        if (mode != ExecutionCommandMode.SHELL) {
            throw new IllegalStateException("direct commands do not expose shell text");
        }
        return shellCommand;
    }

    public String executable() {
        if (mode != ExecutionCommandMode.DIRECT) {
            throw new IllegalStateException("shell commands do not expose an executable");
        }
        return argv.getFirst();
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof ExecutionCommand other)) return false;
        return mode == other.mode && argv.equals(other.argv) && Objects.equals(shellCommand, other.shellCommand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, argv, shellCommand);
    }

    @Override
    public String toString() {
        return mode == ExecutionCommandMode.DIRECT
                ? "ExecutionCommand[mode=DIRECT, argv=" + argv + "]"
                : "ExecutionCommand[mode=SHELL, commandLength=" + shellCommand.length() + "]";
    }

    private static List<String> validateArgv(List<String> values) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, "argv must not be null"));
        if (copy.isEmpty()) throw new IllegalArgumentException("argv must not be empty");
        for (String argument : copy) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("argv contains null or NUL");
            }
        }
        return copy;
    }

    private static String validateShellCommand(String value) {
        String command = Objects.requireNonNull(value, "shell command must not be null");
        if (command.isBlank()) throw new IllegalArgumentException("shell command must not be blank");
        if (command.indexOf('\0') >= 0) throw new IllegalArgumentException("shell command contains NUL");
        if (command.length() > MAX_SHELL_COMMAND_LENGTH) {
            throw new IllegalArgumentException("shell command exceeds maximum length");
        }
        return command;
    }
}
