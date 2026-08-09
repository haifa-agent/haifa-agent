package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.PasteMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Program;
import com.williamcallahan.tui4j.compat.bubbletea.ProgramOption;
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class Tui4jTerminalSpikeTest {
    @Test
    void keepsPrototypeRegionOrderAtRequiredTerminalSizes() {
        for (int[] size : List.of(new int[] {80, 24}, new int[] {120, 40}, new int[] {180, 50})) {
            var model = new Tui4jTerminalSpikeModel(size[0], size[1]);

            String view = model.view();

            assertThat(view)
                    .containsSubsequence(
                            "HAIFA CODING AGENT",
                            "Startup help",
                            "Loaded resources",
                            "Diagnostics",
                            "You",
                            "Pending messages",
                            "Status",
                            "Widgets above",
                            "Type a message",
                            "Widgets below",
                            "Footer");
            assertThat(model.viewportHeight()).isEqualTo(size[1] - 14);
            assertThat(view).contains("杭州", "北京", "😀", "e\u0301");
            assertThat(view).doesNotContain("\uFFFF", "\uFFFD");
        }
    }

    @Test
    void resizesAndAcceptsUnicodeBracketedPasteThroughComponents() {
        var model = new Tui4jTerminalSpikeModel(80, 24);

        model.update(new WindowSizeMessage(120, 40));
        model.update(new PasteMessage("第一行 😀 e\u0301\n第二行"));

        assertThat(model.columns()).isEqualTo(120);
        assertThat(model.rows()).isEqualTo(40);
        assertThat(model.viewportHeight()).isEqualTo(26);
        assertThat(model.editorValue()).isEqualTo("第一行 😀 e\u0301\n第二行");
        assertThat(model.view()).doesNotContain("\uFFFF", "\uFFFD");
    }

    @Test
    void programSendIsTheSingleRuntimeActionEntryPoint() throws Exception {
        var model = new Tui4jTerminalSpikeModel(80, 24);
        var output = new ByteArrayOutputStream();
        try (var inputWriter = new PipedOutputStream();
                var input = new PipedInputStream(inputWriter)) {
            var program = new Program(
                    model,
                    ProgramOption.withInput(input),
                    ProgramOption.withOutput(output),
                    ProgramOption.withEnvironment(List.of("TERM=xterm-256color")),
                    ProgramOption.withoutSignalHandler());
            CompletableFuture<Void> run = CompletableFuture.runAsync(program::run)
                    .orTimeout(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);

            program.waitForInit();
            program.send(new Tui4jTerminalSpikeModel.RuntimeActionMessage("safe runtime action"));
            program.send(new QuitMessage());
            run.get(10, TimeUnit.SECONDS);
        }

        assertThat(model.runtimeNotices()).containsExactly("safe runtime action");
    }

    @Test
    void rendersStableCompactDiagnosticBelowSafeSize() {
        var model = new Tui4jTerminalSpikeModel(40, 10);

        assertThat(model.view())
                .isEqualTo(
                        """
                        Haifa Coding Agent
                        Terminal is too small
                        Required: at least 60x16
                        Current: 40x10
                        Resize the terminal to continue.""");
    }
}
