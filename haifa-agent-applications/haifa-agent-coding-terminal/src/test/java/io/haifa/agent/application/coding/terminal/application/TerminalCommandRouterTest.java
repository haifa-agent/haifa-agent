package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalCommandRouterTest {
    private final TerminalCommandRouter router = new TerminalCommandRouter();

    @Test
    void exposesImplementedTerminalCommandsAndArguments() {
        assertThat(router.route("/new")).isEqualTo(TerminalCommand.NEW);
        assertThat(router.route("/resume")).isEqualTo(TerminalCommand.RESUME);
        assertThat(router.route("/resume matching text")).isEqualTo(TerminalCommand.RESUME);
        assertThat(router.route("/name focused work")).isEqualTo(TerminalCommand.RENAME);
        assertThat(router.route("/rename focused work")).isEqualTo(TerminalCommand.RENAME);
        assertThat(router.route("/archive")).isEqualTo(TerminalCommand.ARCHIVE);
        assertThat(router.route("/delete")).isEqualTo(TerminalCommand.DELETE);
        assertThat(router.route("/compact keep decisions")).isEqualTo(TerminalCommand.COMPACT);
        assertThat(router.route("/reload")).isEqualTo(TerminalCommand.RELOAD);
        assertThat(router.route("/export .haifa-agent/session.jsonl")).isEqualTo(TerminalCommand.EXPORT);
        assertThat(router.route("/settings")).isEqualTo(TerminalCommand.SETTINGS);
        assertThat(router.route("/trust")).isEqualTo(TerminalCommand.TRUST);
        assertThat(router.route("/session")).isEqualTo(TerminalCommand.SESSION);
        assertThat(router.route("/command")).isEqualTo(TerminalCommand.COMMANDS);
        assertThat(router.route("/commands")).isEqualTo(TerminalCommand.COMMANDS);
        assertThat(router.route("/help")).isEqualTo(TerminalCommand.COMMANDS);
        assertThat(router.route("/quit")).isEqualTo(TerminalCommand.QUIT);
    }

    @Test
    void rejectsDeferredCapabilitiesWithoutPretendingTheyExist() {
        assertThat(router.route("/model")).isEqualTo(TerminalCommand.MODEL);
        assertThat(router.route("/login")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/tree")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/fork")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/clone")).isEqualTo(TerminalCommand.NOT_IMPLEMENTED);
        assertThat(router.route("/invented")).isEqualTo(TerminalCommand.UNKNOWN);
    }
}
