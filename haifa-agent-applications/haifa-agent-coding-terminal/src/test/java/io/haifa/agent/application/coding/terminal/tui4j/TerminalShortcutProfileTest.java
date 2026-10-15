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
        assertThat(profile.leader()).isEqualTo("⌃X");
        assertThat(profile.commandPalette()).isEqualTo("⌃P");
        assertThat(profile.modelCycle()).isEqualTo("F2");
        assertThat(profile.sidebarToggle()).isEqualTo("⌃X B");
        assertThat(profile.matchesToggleExpansion(key(KeyType.keySI))).isTrue();
        assertThat(profile.matchesFollowUp(alt(KeyType.keyCR))).isTrue();
        assertThat(profile.matchesRestoreQueuedMessage(alt(KeyType.KeyUp))).isTrue();
        assertThat(profile.matchesFollowUp(key(KeyType.keyCR))).isFalse();
        assertThat(profile.matchesLeader(key(KeyType.keyCAN))).isTrue();
        assertThat(profile.matchesCommandPalette(key(KeyType.keyDLE))).isTrue();
        assertThat(profile.matchesModelCycleNext(key(KeyType.KeyF2))).isTrue();
        assertThat(profile.matchesModelCyclePrevious(alt(KeyType.KeyF2))).isTrue();
        assertThat(profile.matchesModelCyclePrevious(key(KeyType.KeyF14))).isTrue();
    }

    @Test
    void keepsTextLabelsForNonMacHostsWithTheSameTruthfulTerminalMappings() {
        TerminalShortcutProfile profile = TerminalShortcutProfile.forHost(host("Windows 11"));

        assertThat(profile.style()).isEqualTo(TerminalShortcutProfile.Style.STANDARD);
        assertThat(profile.toggleExpansion()).isEqualTo("ctrl+o");
        assertThat(profile.followUp()).isEqualTo("alt+enter");
        assertThat(profile.restoreQueuedMessage()).isEqualTo("alt+up");
        assertThat(profile.leader()).isEqualTo("ctrl+x");
        assertThat(profile.commandPalette()).isEqualTo("ctrl+p");
        assertThat(profile.modelCycle()).isEqualTo("f2");
        assertThat(profile.sidebarToggle()).isEqualTo("ctrl+x b");
        assertThat(profile.matchesToggleExpansion(key(KeyType.keySI))).isTrue();
        assertThat(profile.matchesFollowUp(alt(KeyType.keyCR))).isTrue();
        assertThat(profile.matchesLeader(key(KeyType.keyCAN))).isTrue();
        assertThat(profile.matchesCommandPalette(key(KeyType.keyDLE))).isTrue();
        assertThat(profile.matchesModelCycleNext(key(KeyType.KeyF2))).isTrue();
        assertThat(profile.matchesModelCyclePrevious(alt(KeyType.KeyF2))).isTrue();
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
