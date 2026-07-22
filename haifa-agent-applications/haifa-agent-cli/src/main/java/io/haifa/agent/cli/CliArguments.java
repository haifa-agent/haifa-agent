package io.haifa.agent.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

record CliArguments(
        Optional<String> message,
        Optional<Path> workspace,
        Optional<Path> config,
        Optional<String> model,
        Optional<ApprovalMode> approval,
        Optional<Duration> timeout,
        boolean verbose,
        boolean help) {
    static CliArguments parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        String message = null;
        Path workspace = null;
        Path config = null;
        String model = null;
        ApprovalMode approval = null;
        Duration timeout = null;
        boolean verbose = false;
        boolean help = false;
        List<String> values = new ArrayList<>(List.of(arguments));
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            switch (value) {
                case "-h", "--help" -> help = true;
                case "--verbose" -> verbose = true;
                case "-m", "--message" -> message = requireValue(values, ++index, value);
                case "--workspace" -> workspace = Path.of(requireValue(values, ++index, value));
                case "--config" -> config = Path.of(requireValue(values, ++index, value));
                case "--model" -> model = requireValue(values, ++index, value);
                case "--approval" -> approval = ApprovalMode.parse(requireValue(values, ++index, value));
                case "--timeout" -> timeout = Duration.parse(requireValue(values, ++index, value));
                default -> throw new IllegalArgumentException("unknown option: " + value);
            }
        }
        return new CliArguments(
                optionalText(message), Optional.ofNullable(workspace), Optional.ofNullable(config), optionalText(model),
                Optional.ofNullable(approval), Optional.ofNullable(timeout), verbose, help);
    }

    private static String requireValue(List<String> values, int index, String option) {
        if (index >= values.size()) throw new IllegalArgumentException("missing value for " + option);
        String value = values.get(index).trim();
        if (value.isEmpty() || value.startsWith("-")) throw new IllegalArgumentException("missing value for " + option);
        return value;
    }

    private static Optional<String> optionalText(String value) {
        return value == null ? Optional.empty() : Optional.of(value.trim()).filter(text -> !text.isEmpty());
    }
}
