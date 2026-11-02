package io.haifa.agent.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit parser for the product-private host mode; it does not widen CliArguments. */
record CodingHostArguments(Path workspace, Optional<Path> config, String bind, int port, boolean help) {
    static final int DEFAULT_PORT = 17_861;

    CodingHostArguments {
        Objects.requireNonNull(workspace, "workspace must not be null");
        config = Objects.requireNonNull(config, "config must not be null");
        bind = Objects.requireNonNull(bind, "bind must not be null").trim();
        if (bind.isEmpty()) throw new IllegalArgumentException("host bind address must not be blank");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("host port must be between 0 and 65535");
    }

    static CodingHostArguments parse(String[] arguments, Path defaultWorkspace) {
        Path workspace = defaultWorkspace;
        Path config = null;
        String bind = "127.0.0.1";
        int port = DEFAULT_PORT;
        boolean help = false;
        List<String> values = new ArrayList<>(List.of(arguments));
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            switch (value) {
                case "-h", "--help" -> help = true;
                case "--workspace" -> workspace = Path.of(requireValue(values, ++index, value));
                case "--config" -> config = Path.of(requireValue(values, ++index, value));
                case "--bind" -> bind = requireValue(values, ++index, value);
                case "--port" -> port = parsePort(requireValue(values, ++index, value));
                default -> throw new IllegalArgumentException("unknown host option: " + value);
            }
        }
        Path absoluteWorkspace = workspace.toAbsolutePath().normalize();
        Optional<Path> absoluteConfig =
                Optional.ofNullable(config).map(Path::toAbsolutePath).map(Path::normalize);
        return new CodingHostArguments(absoluteWorkspace, absoluteConfig, bind, port, help);
    }

    CliArguments toCliArguments() {
        List<String> values = new ArrayList<>();
        values.add("--workspace");
        values.add(workspace.toString());
        config.ifPresent(path -> {
            values.add("--config");
            values.add(path.toString());
        });
        return CliArguments.parse(values.toArray(String[]::new));
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("host port must be numeric", exception);
        }
    }

    private static String requireValue(List<String> values, int index, String option) {
        if (index >= values.size()) throw new IllegalArgumentException("missing value for " + option);
        String value = values.get(index).trim();
        if (value.isEmpty() || value.startsWith("-")) throw new IllegalArgumentException("missing value for " + option);
        return value;
    }
}
