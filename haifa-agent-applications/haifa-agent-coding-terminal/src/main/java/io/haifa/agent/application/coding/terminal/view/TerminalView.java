package io.haifa.agent.application.coding.terminal.view;

import java.util.List;
import org.jline.utils.AttributedString;

public record TerminalView(List<AttributedString> lines, int cursorRow, int cursorColumn) {
    public TerminalView {
        lines = List.copyOf(lines);
        if (cursorRow < 0 || cursorColumn < 0) throw new IllegalArgumentException("cursor must not be negative");
    }
}
