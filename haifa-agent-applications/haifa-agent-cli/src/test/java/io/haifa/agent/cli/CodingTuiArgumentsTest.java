package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import io.haifa.agent.core.session.AgentSessionId;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodingTuiArgumentsTest {
    @Test
    void requiresTheConnectEndpoint() {
        assertThatThrownBy(() -> CodingTuiArguments.parse(new String[] {"tui"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tui requires --connect <endpoint>");
    }

    @Test
    void defaultsToTheLastSessionOfTheConnectedHost() {
        CodingTuiArguments parsed =
                CodingTuiArguments.parse(new String[] {"tui", "--connect", "http://127.0.0.1:17861"});

        assertThat(parsed.endpoint()).isEqualTo(URI.create("http://127.0.0.1:17861"));
        assertThat(parsed.startup().mode()).isEqualTo(CodingTerminalStartup.Mode.AUTO_LAST);
        assertThat(parsed.help()).isFalse();
    }

    @Test
    void selectsAnExplicitSessionOrANewOne() {
        CodingTuiArguments selected = CodingTuiArguments.parse(
                new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--session", "session-7"});
        CodingTuiArguments created =
                CodingTuiArguments.parse(new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--new"});

        assertThat(selected.startup().mode()).isEqualTo(CodingTerminalStartup.Mode.SESSION);
        assertThat(selected.startup().sessionId()).contains(new AgentSessionId("session-7"));
        assertThat(created.startup().mode()).isEqualTo(CodingTerminalStartup.Mode.EMPTY);
        assertThat(created.startup().sessionId()).isEmpty();
    }

    @Test
    void refusesSessionAndNewTogether() {
        assertThatThrownBy(() -> CodingTuiArguments.parse(
                        new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--session", "session-7", "--new"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--session cannot be used with --new");
    }

    @Test
    void refusesTheEmbeddedOptionsTheHostOwns() {
        assertThatThrownBy(() -> CodingTuiArguments.parse(
                        new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--config", "haifa-coding.yaml"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--config is not accepted by tui --connect");
        assertThatThrownBy(() -> CodingTuiArguments.parse(
                        new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--workspace", "/work"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--workspace is not accepted by tui --connect");
        assertThatThrownBy(() -> CodingTuiArguments.parse(
                        new String[] {"tui", "--connect", "http://127.0.0.1:17861", "-m", "task"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-m is not accepted by tui --connect");
    }

    @Test
    void refusesUnknownAndRepeatedOptions() {
        assertThatThrownBy(() ->
                        CodingTuiArguments.parse(new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--nope"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown tui option: --nope");
        assertThatThrownBy(() -> CodingTuiArguments.parse(new String[] {
                    "tui", "--connect", "http://127.0.0.1:17861", "--connect", "http://127.0.0.1:17862"
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--connect may only be specified once");
    }

    @Test
    void refusesANonHttpEndpoint() {
        assertThatThrownBy(() -> CodingTuiArguments.parse(new String[] {"tui", "--connect", "file:///tmp/host"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tui --connect requires an http or https endpoint");
    }

    @Test
    void helpIsRecognizedWithoutAnEndpoint() {
        CodingTuiArguments parsed = CodingTuiArguments.parse(new String[] {"tui", "--help"});

        assertThat(parsed.help()).isTrue();
    }

    @Test
    void onlyTheLeadingTuiTokenSelectsTheRemoteMode() {
        assertThat(CodingTuiArguments.isRemoteCommand(new String[] {"tui", "--connect", "http://127.0.0.1:1"}))
                .isTrue();
        assertThat(CodingTuiArguments.isRemoteCommand(new String[] {"host"})).isFalse();
        assertThat(CodingTuiArguments.isRemoteCommand(new String[] {"--terminal"}))
                .isFalse();
        assertThat(CodingTuiArguments.isRemoteCommand(new String[0])).isFalse();
        assertThat(CodingTuiArguments.isRemoteCommand(null)).isFalse();
    }

    @Test
    void remoteModeNeverTouchesTheEmbeddedTerminalRunner() {
        AtomicInteger embeddedLaunches = new AtomicInteger();
        AtomicInteger remoteLaunches = new AtomicInteger();
        var main = new HaifaCliMain(
                (workspace, configuration, startup, output, trace) -> embeddedLaunches.incrementAndGet(),
                (endpoint, startup, output) -> remoteLaunches.incrementAndGet());
        CodingTuiArguments parsed =
                CodingTuiArguments.parse(new String[] {"tui", "--connect", "http://127.0.0.1:17861", "--new"});

        main.runRemoteMode(parsed, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        assertThat(remoteLaunches).hasValue(1);
        assertThat(embeddedLaunches).hasValue(0);
    }
}
