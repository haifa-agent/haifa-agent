package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.Key;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalShortcutProfileTest {
    @Test
    void usesMacSpecialLabelsThatMatchTheControlAndOptionEventsActuallyHandled() {
        TerminalShortcutProfile profile = TerminalShortcutProfile.forHost(host("Mac OS X"));

        assertThat(profile.style()).isEqualTo(TerminalShortcutProfile.Style.MAC_SPECIAL);
        assertThat(profile.toggleExpansion()).isEqualTo("⌃O");
        assertThat(profile.followUp()).isEqualTo("⌥↩");
        assertThat(profile.restoreQueuedMessage()).isEqualTo("⌥↑");
        assertThat(profile.newline()).isEqualTo("⇧↩/⌃J");
        assertThat(profile.matchesToggleExpansion(key(KeyType.keySI))).isTrue();
        assertThat(profile.matchesFollowUp(alt(KeyType.keyCR))).isTrue();
        assertThat(profile.matchesRestoreQueuedMessage(alt(KeyType.KeyUp))).isTrue();
        assertThat(profile.matchesFollowUp(key(KeyType.keyCR))).isFalse();
    }

    @Test
    void keepsTextLabelsForNonMacHostsWithTheSameTruthfulTerminalMappings() {
        TerminalShortcutProfile profile = TerminalShortcutProfile.forHost(host("Windows 11"));

        assertThat(profile.style()).isEqualTo(TerminalShortcutProfile.Style.STANDARD);
        assertThat(profile.toggleExpansion()).isEqualTo("ctrl+o");
        assertThat(profile.followUp()).isEqualTo("alt+enter");
        assertThat(profile.restoreQueuedMessage()).isEqualTo("alt+up");
        assertThat(profile.matchesToggleExpansion(key(KeyType.keySI))).isTrue();
        assertThat(profile.matchesFollowUp(alt(KeyType.keyCR))).isTrue();
    }

    private static TerminalHostInfo host(String osName) {
        return TerminalHostInfo.detect(
                Map.of(
                        "os.name", osName,
                        "os.version", "1",
                        "os.arch", "test-arch",
                        "java.version", "21"),
                List.of());
    }

    private static KeyPressMessage key(KeyType type) {
        return new KeyPressMessage(new Key(type));
    }

    private static KeyPressMessage alt(KeyType type) {
        return new KeyPressMessage(new Key(type, true));
    }
}
