package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.PasteMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage-A-only model used to prove tui4j's event loop and core components before production
 * cutover. It deliberately has no Coding Agent, persistence, provider, filesystem, or execution
 * dependency.
 */
final class Tui4jTerminalSpikeModel implements Model {
    private static final int MIN_COLUMNS = 60;
    private static final int MIN_ROWS = 16;
    private static final int FIXED_REGION_ROWS = 14;

    private final Viewport transcript;
    private final Textarea editor;
    private final List<String> runtimeNotices = new ArrayList<>();
    private int columns;
    private int rows;

    Tui4jTerminalSpikeModel(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        transcript = Viewport.create(columns, viewportHeight(rows));
        transcript.setContent(String.join(
                "\n",
                "You",
                "请检查 Unicode：杭州 / 北京 / 😀 / e\u0301",
                "",
                "Assistant",
                "Stage A uses a bounded tui4j viewport.",
                "A deliberately long line is kept inside the viewport instead of moving the editor or footer."));
        editor = new Textarea();
        editor.setWidth(columns);
        editor.setHeight(3);
        editor.setMaxHeight(3);
        editor.setShowLineNumbers(false);
        editor.setPrompt("┃ ");
        editor.setPlaceholder("Type a message, /command, @file, !command, or !!command");
        editor.focus();
    }

    @Override
    public Command init() {
        return Command.none();
    }

    @Override
    public UpdateResult<Tui4jTerminalSpikeModel> update(Message message) {
        if (message instanceof WindowSizeMessage resized) {
            columns = resized.width();
            rows = resized.height();
            transcript.setWidth(columns);
            transcript.setHeight(viewportHeight(rows));
            editor.setWidth(columns);
            return UpdateResult.from(this);
        }
        if (message instanceof RuntimeActionMessage action) {
            runtimeNotices.add(action.safeSummary());
            transcript.setContent(transcriptContent());
            transcript.gotoBottom();
            return UpdateResult.from(this);
        }
        if (message instanceof FailureMessage failure) {
            throw new IllegalStateException(failure.code());
        }
        if (message instanceof KeyPressMessage key
                && (key.type() == KeyType.keyETX
                        || key.type() == KeyType.keyESC
                        || (key.type() == KeyType.KeyRunes && "q".equals(key.key())))) {
            return UpdateResult.from(this, Command.quit());
        }
        if (message instanceof KeyPressMessage || message instanceof PasteMessage) {
            editor.update(message);
        }
        return UpdateResult.from(this);
    }

    @Override
    public String view() {
        if (columns < MIN_COLUMNS || rows < MIN_ROWS) {
            return String.join(
                    "\n",
                    "Haifa Coding Agent",
                    "Terminal is too small",
                    "Required: at least " + MIN_COLUMNS + "x" + MIN_ROWS,
                    "Current: " + columns + "x" + rows,
                    "Resize the terminal to continue.");
        }
        return String.join(
                "\n",
                "HAIFA CODING AGENT",
                "Startup help  / for commands  @ for files",
                "Loaded resources  AGENTS.md: loaded  Skills: 0",
                "Diagnostics  tui4j Stage A spike; no provider or persistence",
                transcript.view(),
                "Pending messages  none",
                "Status  idle",
                "Widgets above  none",
                editor.view(),
                "Widgets below  none",
                "Footer  Enter sends / Shift+Enter adds a line / Esc interrupts",
                "deepseek-chat  context 0%  session spike");
    }

    String editorValue() {
        return editor.value();
    }

    List<String> runtimeNotices() {
        return List.copyOf(runtimeNotices);
    }

    int columns() {
        return columns;
    }

    int rows() {
        return rows;
    }

    int viewportHeight() {
        return transcript.getHeight();
    }

    private int viewportHeight(int terminalRows) {
        return Math.max(2, terminalRows - FIXED_REGION_ROWS);
    }

    private String transcriptContent() {
        StringBuilder content = new StringBuilder();
        content.append(String.join(
                "\n",
                "You",
                "请检查 Unicode：杭州 / 北京 / 😀 / e\u0301",
                "",
                "Assistant",
                "Stage A uses a bounded tui4j viewport.",
                "A deliberately long line is kept inside the viewport instead of moving the editor or footer."));
        for (String notice : runtimeNotices) {
            content.append("\n\nRuntime\n").append(notice);
        }
        return content.toString();
    }

    record RuntimeActionMessage(String safeSummary) implements Message {
        RuntimeActionMessage {
            if (safeSummary == null || safeSummary.isBlank()) {
                throw new IllegalArgumentException("safeSummary must not be blank");
            }
        }
    }

    record FailureMessage(String code) implements Message {}
}
