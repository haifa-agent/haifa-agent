package io.haifa.agent.application.coding.terminal.event;

import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.runtime.api.AgentRunEvent;
import java.util.List;

public sealed interface TerminalUiAction {
    record SessionLoaded(CodingSessionView view, List<String> resources) implements TerminalUiAction {}

    record RunEventReceived(AgentRunEvent event) implements TerminalUiAction {}

    record UserMessageCommitted(String id, String text) implements TerminalUiAction {}

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
