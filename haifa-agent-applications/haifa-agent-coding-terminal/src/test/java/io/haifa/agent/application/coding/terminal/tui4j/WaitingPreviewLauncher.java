package io.haifa.agent.application.coding.terminal.tui4j;

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
import io.haifa.agent.application.coding.terminal.state.WaitingMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * No-API-key local preview for the Waiting-above-editor UI.
 *
 * <p>Drives {@link CodingTerminalController} with {@link FakeClient} (active Run, no model calls)
 * so machines without provider credentials can still inspect the Waiting region and Esc behavior.
 *
 * <pre>
 *   ./mvnw -pl :haifa-agent-coding-terminal -am -DskipTests test-compile
 *   ./mvnw -pl :haifa-agent-coding-terminal -DskipTests exec:java \
 *     -Dexec.classpathScope=test \
 *     -Dexec.mainClass=io.haifa.agent.application.coding.terminal.tui4j.WaitingPreviewLauncher \
 *     -Dexec.args=target/waiting-preview.txt
 * </pre>
 *
 * <p>Output stays under {@code target/} (or an explicit path under {@code target/} /
 * {@code @TempDir}) so acceptance dumps do not land in the working directory.
 */
public final class WaitingPreviewLauncher {
    private WaitingPreviewLauncher() {}

    public static void main(String[] args) throws Exception {
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));

        FakeClient client = new FakeClient(CodingTerminalTestSupport.activeView());
        CodingTerminalController controller = CodingTerminalTestSupport.controller(client);
        controller.open(CodingTerminalTestSupport.SESSION_ID);
        controller.accept(new TerminalInput(TerminalInput.Kind.SUBMIT, "先别改 assertion，只修 checkpoint recovery 路径。"));

        if (controller.state().waiting().isEmpty()) {
            throw new IllegalStateException("expected Waiting draft after active Enter");
        }

        // Esc cancels Waiting, not the Run.
        controller.accept(new TerminalInput(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
        if (controller.state().waiting().isPresent()) {
            throw new IllegalStateException("Esc should clear Waiting");
        }
        if (!client.cancelledSessions.isEmpty() || controller.state().cancelArmed()) {
            throw new IllegalStateException("Esc must not cancel/arm Run while Waiting exists");
        }

        controller.accept(new TerminalInput(TerminalInput.Kind.SUBMIT, "先别改 assertion，只修 checkpoint recovery 路径。"));
        TerminalUiState state = withDemoTranscript(controller.state());
        WaitingMessage waiting = state.waiting().orElseThrow();
        if (waiting.phase() != WaitingMessage.Phase.WAITING) {
            throw new IllegalStateException("expected WAITING phase, got " + waiting.phase());
        }

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
        Path output = resolvePreviewOutput(args);
        Files.createDirectories(output.getParent());
        Files.writeString(output, rendered + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println(rendered);
        System.out.println();
        System.out.println("Wrote Waiting preview (FakeClient, no API key) to " + output.toAbsolutePath());
        System.out.println("steered="
                + client.steeredMessages.size()
                + " cancelledRuns="
                + client.cancelledSessions.size()
                + " waiting="
                + controller.state().waiting().isPresent());
    }

    private static Path resolvePreviewOutput(String[] args) {
        Path output = Path.of(args.length > 0 ? args[0] : "target/waiting-preview.txt");
        Path normalized = output.toAbsolutePath().normalize();
        Path targetRoot = Path.of("target").toAbsolutePath().normalize();
        if (!normalized.startsWith(targetRoot)) {
            throw new IllegalArgumentException("preview output must stay under target/: " + output);
        }
        return output;
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
