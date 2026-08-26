package io.haifa.agent.application.coding.terminal.event;

import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionView;
import java.util.List;

public sealed interface TerminalUiAction {
    record SessionLoaded(CodingSessionView view, List<String> resources) implements TerminalUiAction {}

    record HistoryLoaded(CodingSessionHistoryPage history) implements TerminalUiAction {}

    record SessionCleared(String status) implements TerminalUiAction {}

    record ResourcesChanged(List<String> resources) implements TerminalUiAction {}

    record ContextChanged(String indicator) implements TerminalUiAction {}

    record ShellCompleted(String command, String summary, String status) implements TerminalUiAction {}

    record ExportCompleted(String logicalPath, int messageCount) implements TerminalUiAction {}

    record DeviceLoginInstructionsPresented(String verificationUri, String userCode) implements TerminalUiAction {}

    record BrowserLoginInstructionsPresented(String connectionName, String authorizationUri)
            implements TerminalUiAction {
        public BrowserLoginInstructionsPresented(String authorizationUri) {
            this("ChatGPT", authorizationUri);
        }

        @Override
        public String toString() {
            return "BrowserLoginInstructionsPresented[connectionName=" + connectionName
                    + ", authorizationUri=[REDACTED_AUTH_URL]]";
        }
    }

    record AuthenticationProgressed(String connectionName, CodingAuthenticationProgressView.Phase phase)
            implements TerminalUiAction {
        public AuthenticationProgressed(CodingAuthenticationProgressView.Phase phase) {
            this("ChatGPT Codex", phase);
        }
    }

    record AuthenticationCompleted(String connectionName, boolean unofficialLocalCompatibility)
            implements TerminalUiAction {
        public AuthenticationCompleted(boolean unofficialLocalCompatibility) {
            this("ChatGPT Codex", unofficialLocalCompatibility);
        }
    }

    record AuthenticationFailed(String connectionName, String code) implements TerminalUiAction {
        public AuthenticationFailed(String code) {
            this("ChatGPT Codex", code);
        }
    }

    record RunEventReceived(AgentRunEvent event) implements TerminalUiAction {}

    record RunOutputReceived(AgentRunOutputEvent event) implements TerminalUiAction {}

    record InteractionPresented(InteractionView interaction) implements TerminalUiAction {}

    record InteractionReceiptReceived(InteractionResponseReceipt receipt) implements TerminalUiAction {}

    record UserMessageCommitted(String id, String text) implements TerminalUiAction {}

    record UserMessageRejected(String id) implements TerminalUiAction {}

    record EditorChanged(String buffer, int cursor) implements TerminalUiAction {}

    record PendingChanged(List<PendingMessage> messages) implements TerminalUiAction {}

    record StatusChanged(String status) implements TerminalUiAction {}

    record SelectorOpened(TerminalSelector selector) implements TerminalUiAction {}

    record SelectorMoved(int delta) implements TerminalUiAction {}

    record SelectorClosed() implements TerminalUiAction {}

    record ToggleExpanded(String itemId) implements TerminalUiAction {}

    record TerminalResized(int columns, int rows) implements TerminalUiAction {}

    record RecoverableFailure(String code) implements TerminalUiAction {}

    record ExitRequested() implements TerminalUiAction {}
}
