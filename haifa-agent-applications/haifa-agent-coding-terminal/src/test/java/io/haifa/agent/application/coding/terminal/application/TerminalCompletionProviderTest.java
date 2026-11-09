package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalCompletionProviderTest {
    @Test
    void remoteActionsRemainDiscoverableWithoutOfferingCredentialCollection() {
        var options = CodingTerminalConnectionOptions.remoteV1("/host/workspace", true);
        var completion = new TerminalCompletionProvider(List::of, options);
        assertThat(completion.suggestions("/conn")).isEmpty();
        assertThat(completion.suggestions("/")).contains("/approval", "/trust", "/reload", "/export");
        assertThat(options.commandLabel("/connect")).contains("unavailable", "trusted Host");
        assertThat(options.commandLabel("/approval")).contains("pending/reopen", "session consent unavailable");
        assertThat(options.commandLabel("/export")).isEqualTo("/export · Host");
        assertThat(options.toolOutputPreviewEnabled()).isTrue();
        assertThat(options.sessionConsentEnabled()).isFalse();
        assertThat(options.credentialSetupEnabled()).isFalse();
    }

    @Test
    void listsFrequentWorkflowCommandsBeforeSessionMaintenanceCommands() {
        assertThat(TerminalCompletionProvider.COMMANDS)
                .containsExactly(
                        "/connect",
                        "/logout",
                        "/account",
                        "/model",
                        "/new",
                        "/resume",
                        "/compact",
                        "/session",
                        "/trust",
                        "/approval",
                        "/reload",
                        "/rename",
                        "/export",
                        "/shell",
                        "/archive",
                        "/delete",
                        "/commands",
                        "/theme",
                        "/mouse",
                        "/help",
                        "/quit");
    }

    @Test
    void filtersEveryDotPrefixedPathSegmentFromAtCompletion() {
        var completion = new TerminalCompletionProvider(() -> List.of(
                ".hidden",
                ".config/settings.json",
                "src/.secret.java",
                "src/.private/value.txt",
                "src/",
                "src/App.java"));

        assertThat(completion.suggestions("@")).containsExactly("@src/", "@src/App.java");
    }
}
