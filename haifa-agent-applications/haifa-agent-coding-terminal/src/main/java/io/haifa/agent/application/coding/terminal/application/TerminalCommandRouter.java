package io.haifa.agent.application.coding.terminal.application;

import java.util.Locale;
import java.util.Set;

/** Stable command boundary. Unsupported roadmap commands never open fake selectors. */
public final class TerminalCommandRouter {
    public static final String CAPABILITY_NOT_IMPLEMENTED = "CAPABILITY_NOT_IMPLEMENTED";
    public static final String COMMAND_UNKNOWN = "COMMAND_UNKNOWN";
    private static final Set<String> DEFERRED = Set.of("/tree", "/fork", "/clone");

    public TerminalCommand route(String input) {
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (!value.startsWith("/")) {
            return TerminalCommand.MESSAGE;
        }
        String command = value.split("\\s+", 2)[0];
        return switch (command) {
            case "/new" -> TerminalCommand.NEW;
            case "/resume" -> TerminalCommand.RESUME;
            case "/name", "/rename" -> TerminalCommand.RENAME;
            case "/archive" -> TerminalCommand.ARCHIVE;
            case "/delete" -> TerminalCommand.DELETE;
            case "/reload" -> TerminalCommand.RELOAD;
            case "/compact" -> TerminalCommand.COMPACT;
            case "/export" -> TerminalCommand.EXPORT;
            case "/login" -> TerminalCommand.LOGIN;
            case "/logout" -> TerminalCommand.LOGOUT;
            case "/account" -> TerminalCommand.ACCOUNT;
            case "/model" -> TerminalCommand.MODEL;
            case "/settings" -> TerminalCommand.SETTINGS;
            case "/trust" -> TerminalCommand.TRUST;
            case "/session" -> TerminalCommand.SESSION;
            case "/command", "/commands", "/help" -> TerminalCommand.COMMANDS;
            case "/quit" -> TerminalCommand.QUIT;
            default -> DEFERRED.contains(command) ? TerminalCommand.NOT_IMPLEMENTED : TerminalCommand.UNKNOWN;
        };
    }
}
