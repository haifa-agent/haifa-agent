package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import java.util.Objects;

/** Keeps shortcut labels and the terminal key events that implement them in one profile. */
record TerminalShortcutProfile(
        Style style,
        String interrupt,
        String clear,
        String toggleExpansion,
        String followUp,
        String restoreQueuedMessage,
        String newline) {
    TerminalShortcutProfile {
        style = Objects.requireNonNull(style, "style must not be null");
        interrupt = required(interrupt, "interrupt");
        clear = required(clear, "clear");
        toggleExpansion = required(toggleExpansion, "toggleExpansion");
        followUp = required(followUp, "followUp");
        restoreQueuedMessage = required(restoreQueuedMessage, "restoreQueuedMessage");
        newline = required(newline, "newline");
    }

    static TerminalShortcutProfile standard() {
        return new TerminalShortcutProfile(
                Style.STANDARD, "esc", "ctrl+c", "ctrl+o", "alt+enter", "alt+up", "shift+enter/ctrl+j");
    }

    static TerminalShortcutProfile forHost(TerminalHostInfo host) {
        Objects.requireNonNull(host, "host must not be null");
        if (host.operatingSystem() != TerminalHostInfo.OperatingSystem.MACOS) return standard();
        return new TerminalShortcutProfile(Style.MAC_SPECIAL, "esc", "⌃C", "⌃O", "⌥↩", "⌥↑", "⇧↩/⌃J");
    }

    boolean matchesToggleExpansion(KeyPressMessage key) {
        return key.type() == KeyType.keySI;
    }

    boolean matchesFollowUp(KeyPressMessage key) {
        return key.alt() && key.type() == KeyType.keyCR;
    }

    boolean matchesRestoreQueuedMessage(KeyPressMessage key) {
        return key.alt() && key.type() == KeyType.KeyUp;
    }

    private static String required(String value, String name) {
        String safe = Objects.requireNonNull(value, name + " must not be null").strip();
        if (safe.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return safe;
    }

    enum Style {
        STANDARD,
        MAC_SPECIAL
    }
}
