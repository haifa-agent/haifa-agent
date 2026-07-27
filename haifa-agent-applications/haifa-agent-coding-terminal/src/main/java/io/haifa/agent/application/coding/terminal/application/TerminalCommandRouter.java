package io.haifa.agent.application.coding.terminal.application;

import java.util.Locale;
import java.util.Set;

/** Stable command boundary. Unsupported roadmap commands never open fake selectors. */
public final class TerminalCommandRouter {
    public static final String CAPABILITY_NOT_IMPLEMENTED = "CAPABILITY_NOT_IMPLEMENTED";
    public static final String COMMAND_UNKNOWN = "COMMAND_UNKNOWN";
    private static final Set<String> DEFERRED = Set.of("/model", "/login", "/tree", "/compact");

    public TerminalCommand route(String input) {
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (!value.startsWith("/")) {
            return TerminalCommand.MESSAGE;
        }
        return switch (value) {
            case "/new" -> TerminalCommand.NEW;
            case "/resume" -> TerminalCommand.RESUME;
            case "/settings" -> TerminalCommand.SETTINGS;
            case "/trust" -> TerminalCommand.TRUST;
            case "/session" -> TerminalCommand.SESSION;
            case "/command", "/commands" -> TerminalCommand.COMMANDS;
            case "/quit" -> TerminalCommand.QUIT;
            default -> DEFERRED.contains(value) ? TerminalCommand.NOT_IMPLEMENTED : TerminalCommand.UNKNOWN;
        };
    }
}
