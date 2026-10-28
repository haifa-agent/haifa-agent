package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.compat.x.ansi.Strip;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalController;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport;
import io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.FakeClient;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.state.TerminalActivity;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Writes a no-API-key Waiting UI dump for local visual inspection. */
class WaitingPreviewIT {
    @BeforeAll
    static void configureHeadlessTerminalInfo() {
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @Test
    void dumpsWaitingPreviewWithoutModelCredentials() throws Exception {
        FakeClient client = new FakeClient(CodingTerminalTestSupport.activeView());
        CodingTerminalController controller = CodingTerminalTestSupport.controller(client);
        controller.open(CodingTerminalTestSupport.SESSION_ID);
        controller.accept(new TerminalInput(TerminalInput.Kind.SUBMIT, "先别改 assertion，只修 checkpoint recovery 路径。"));
        assertThat(controller.state().waiting()).isPresent();

        controller.accept(new TerminalInput(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        assertThat(controller.state().waiting()).isEmpty();
        assertThat(client.cancelledSessions).isEmpty();
        assertThat(controller.state().cancelArmed()).isFalse();
        assertThat(controller.state().currentRunId()).isPresent();

        controller.accept(new TerminalInput(TerminalInput.Kind.SUBMIT, "先别改 assertion，只修 checkpoint recovery 路径。"));
        TerminalUiState state = withDemoTranscript(controller.state());

        Tui4jTerminalView view = new Tui4jTerminalView();
        Viewport transcript = Viewport.create(state.columns(), Math.max(8, state.rows() - 14));
        transcript.setContent(view.transcriptContent(state));
        Textarea editor = new Textarea();
        editor.setWidth(state.columns());
        editor.setHeight(3);
        editor.setMaxHeight(3);
        editor.setShowLineNumbers(false);
        editor.setPrompt("┃ ");
        editor.setPlaceholder("Type a message…");
        editor.setValue(state.editorBuffer());
        editor.focus();

        String rendered = Strip.strip(
                view.render(state, transcript, editor, true, false, Duration.ofSeconds(12), 0, false, false));
        Path output = Path.of("target/waiting-preview.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, rendered + System.lineSeparator(), StandardCharsets.UTF_8);

        assertThat(rendered).contains("Waiting", "Send Now enter", "Edit e", "Cancel esc", "checkpoint recovery");
        assertThat(client.steeredMessages).isEmpty();
    }

    private static TerminalUiState withDemoTranscript(TerminalUiState state) {
        List<TranscriptItem> transcript = List.of(
                new TranscriptItem(
                        "u1",
                        TranscriptItem.Kind.USER,
                        "You",
                        "Fix the failing tests in haifa-agent-runtime-core",
                        "SENT",
                        true),
                new TranscriptItem(
                        "t1", TranscriptItem.Kind.TOOL, "Read File · RuntimeCoreTest.java", "", "SUCCEEDED", false),
                new TranscriptItem(
                        "t2", TranscriptItem.Kind.TOOL, "Apply Patch · RuntimeCore.java", "", "SUCCEEDED", false),
                new TranscriptItem(
                        "t3",
                        TranscriptItem.Kind.TOOL,
                        "Run Command · ./mvnw -pl :haifa-agent-runtime-core -am test",
                        "",
                        "RUNNING",
                        false),
                new TranscriptItem(
                        "a1", TranscriptItem.Kind.ASSISTANT, "Assistant", "正在跑测试，等待结果后继续……", "STREAMING", true));
        return new TerminalUiState(
                state.header(),
                List.of("AGENTS.md", "14 tools"),
                transcript,
                state.pending(),
                "WORKING",
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                state.footer(),
                Math.max(100, state.columns()),
                Math.max(30, state.rows()),
                state.session(),
                state.currentRunId(),
                state.appliedCursor(),
                state.seenEventIds(),
                state.recoverableError(),
                new TerminalActivity(1, "Run Command"),
                false,
                state.sidebarVisible(),
                state.sidebarExplicitlyToggled(),
                state.themeName(),
                state.cancelArmed(),
                state.waiting());
    }
}
