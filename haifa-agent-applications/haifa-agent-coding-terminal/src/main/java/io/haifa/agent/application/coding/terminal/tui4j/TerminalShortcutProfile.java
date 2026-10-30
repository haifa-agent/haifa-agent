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
        String editWaiting,
        String restoreQueuedMessage,
        String newline,
        String leader,
        String commandPalette,
        String modelCycle,
        String sidebarToggle) {
    TerminalShortcutProfile {
        style = Objects.requireNonNull(style, "style must not be null");
        interrupt = required(interrupt, "interrupt");
        clear = required(clear, "clear");
        toggleExpansion = required(toggleExpansion, "toggleExpansion");
        followUp = required(followUp, "followUp");
        editWaiting = required(editWaiting, "editWaiting");
        restoreQueuedMessage = required(restoreQueuedMessage, "restoreQueuedMessage");
        newline = required(newline, "newline");
        leader = required(leader, "leader");
        commandPalette = required(commandPalette, "commandPalette");
        modelCycle = required(modelCycle, "modelCycle");
        sidebarToggle = required(sidebarToggle, "sidebarToggle");
    }

    static TerminalShortcutProfile standard() {
        return new TerminalShortcutProfile(
                Style.STANDARD,
                "esc",
                "ctrl+c",
                "ctrl+o",
                "alt+enter",
                "alt+e",
                "alt+up",
                "shift+enter/ctrl+j",
                "ctrl+x",
                "ctrl+p",
                "f2",
                "ctrl+x b");
    }

    static TerminalShortcutProfile forHost(TerminalHostInfo host) {
        Objects.requireNonNull(host, "host must not be null");
        if (host.operatingSystem() != TerminalHostInfo.OperatingSystem.MACOS) return standard();
        return new TerminalShortcutProfile(
                Style.MAC_SPECIAL, "esc", "⌃C", "⌃O", "⌥↩", "⌥E", "⌥↑", "⇧↩/⌃J", "⌃X", "⌃P", "F2", "⌃X B");
    }

    boolean matchesToggleExpansion(KeyPressMessage key) {
        return key.type() == KeyType.keySI;
    }

    String technicalDetails() {
        return style == Style.MAC_SPECIAL ? "⌃X T" : "ctrl+x t";
    }

    boolean matchesFollowUp(KeyPressMessage key) {
        return key.alt() && key.type() == KeyType.keyCR;
    }

    /** Keeps bare {@code e} free for typing; a bare rune would swallow the first character of a message. */
    boolean matchesEditWaiting(KeyPressMessage key) {
        if (!key.alt() || key.type() != KeyType.KeyRunes) return false;
        char[] runes = key.runes();
        return runes != null && runes.length == 1 && (runes[0] == 'e' || runes[0] == 'E');
    }

    boolean matchesRestoreQueuedMessage(KeyPressMessage key) {
        return key.alt() && key.type() == KeyType.KeyUp;
    }

    boolean matchesLeader(KeyPressMessage key) {
        return key.type() == KeyType.keyCAN;
    }

    boolean matchesCommandPalette(KeyPressMessage key) {
        return key.type() == KeyType.keyDLE;
    }

    boolean matchesModelCycleNext(KeyPressMessage key) {
        return key.type() == KeyType.KeyF2 && !key.alt();
    }

    boolean matchesModelCyclePrevious(KeyPressMessage key) {
        return (key.type() == KeyType.KeyF2 && key.alt()) || key.type() == KeyType.KeyF14;
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
