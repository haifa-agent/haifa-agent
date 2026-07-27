package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalCommandRouterTest {
    private final TerminalCommandRouter router = new TerminalCommandRouter();

    @Test
    void exposesOnlyPhaseTwoCommands() {
        assertThat(router.route("/new")).isEqualTo(TerminalCommand.NEW);
        assertThat(router.route("/resume")).isEqualTo(TerminalCommand.RESUME);
        assertThat(router.route("/settings")).isEqualTo(TerminalCommand.SETTINGS);
        assertThat(router.route("/trust")).isEqualTo(TerminalCommand.TRUST);
        assertThat(router.route("/session")).isEqualTo(TerminalCommand.SESSION);
        assertThat(router.route("/command")).isEqualTo(TerminalCommand.COMMANDS);
        assertThat(router.route("/commands")).isEqualTo(TerminalCommand.COMMANDS);
        assertThat(router.route("/quit")).isEqualTo(TerminalCommand.QUIT);
    }

    @Test
    void rejectsDeferredCapabilitiesWithoutPretendingTheyExist() {
        assertThat(router.route("/model")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/login")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/tree")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/compact")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/invented")).isEqualTo(TerminalCommand.UNKNOWN);
    }
}
