package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.core.session.AgentSessionId;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit parser for the remote TUI mode: {@code tui --connect <loopback-endpoint> [--session ID | --new]}.
 *
 * <p>The remote mode is a thin client, so it accepts no workspace, configuration, model or approval options:
 * the Host owns the workspace, Runtime, credentials and the database. Rejecting those options here keeps the
 * process from acquiring local state before the transport even connects.
 */
record CodingTuiArguments(URI endpoint, CodingTerminalStartup startup, boolean help) {
    static final String COMMAND = "tui";

    CodingTuiArguments {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(startup, "startup must not be null");
        if (!endpoint.isAbsolute() || endpoint.getScheme() == null) {
            throw new IllegalArgumentException("tui --connect requires an absolute endpoint URI");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("tui --connect endpoint must name a host");
        }
    }

    /** Whether the leading subcommand selects the remote mode, which must be dispatched before local assembly. */
    static boolean isRemoteCommand(String[] arguments) {
        return arguments != null && arguments.length > 0 && COMMAND.equals(arguments[0]);
    }

    static CodingTuiArguments parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (!isRemoteCommand(arguments)) throw new IllegalArgumentException("missing tui command");
        String connect = null;
        String session = null;
        boolean newSession = false;
        boolean help = false;
        List<String> values = new ArrayList<>(List.of(arguments));
        for (int index = 1; index < values.size(); index++) {
            String value = values.get(index);
            switch (value) {
                case "-h", "--help" -> help = true;
                case "--connect" -> {
                    if (connect != null) throw new IllegalArgumentException("--connect may only be specified once");
                    connect = requireValue(values, ++index, value);
                }
                case "--session" -> {
                    if (session != null) throw new IllegalArgumentException("--session may only be specified once");
                    session = requireValue(values, ++index, value);
                }
                case "--new" -> {
                    if (newSession) throw new IllegalArgumentException("--new may only be specified once");
                    newSession = true;
                }
                case "--workspace",
                        "--config",
                        "--model",
                        "--approval",
                        "--timeout",
                        "--trace",
                        "--trace-file",
                        "-m",
                        "--message",
                        "--terminal",
                        "--quiet",
                        "--verbose",
                        "resume" ->
                    throw new IllegalArgumentException(value
                            + " is not accepted by tui --connect; the Host owns the workspace, Runtime and"
                            + " configuration");
                default -> throw new IllegalArgumentException("unknown tui option: " + value);
            }
        }
        if (help && connect == null) {
            // Help stays side-effect free even when the mandatory endpoint is missing.
            return new CodingTuiArguments(URI.create("http://127.0.0.1"), CodingTerminalStartup.autoLast(), true);
        }
        if (connect == null) throw new IllegalArgumentException("tui requires --connect <endpoint>");
        if (session != null && newSession) throw new IllegalArgumentException("--session cannot be used with --new");
        URI endpoint = parseEndpoint(connect);
        CodingTerminalStartup startup;
        if (session != null) {
            startup = CodingTerminalStartup.session(new AgentSessionId(session), Optional.empty());
        } else if (newSession) {
            startup = CodingTerminalStartup.empty();
        } else {
            startup = CodingTerminalStartup.autoLast();
        }
        return new CodingTuiArguments(endpoint, startup, help);
    }

    private static URI parseEndpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("tui --connect is not a valid URI", exception);
        }
        String scheme = endpoint.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("tui --connect requires an http or https endpoint");
        }
        return endpoint;
    }

    private static String requireValue(List<String> values, int index, String option) {
        if (index >= values.size()) throw new IllegalArgumentException("missing value for " + option);
        String value = values.get(index).trim();
        if (value.isEmpty() || value.startsWith("-")) throw new IllegalArgumentException("missing value for " + option);
        return value;
    }
}
