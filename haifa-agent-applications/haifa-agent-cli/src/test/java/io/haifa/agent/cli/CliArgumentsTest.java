package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CliArgumentsTest {
    @Test
    void parsesOneShotCommand() {
        CliArguments values = CliArguments.parse(new String[] {
            "-m",
            "fix the test",
            "--workspace",
            "demo",
            "--approval",
            "auto",
            "--timeout",
            "PT2M",
            "--trace",
            "jsonl",
            "--trace-file",
            "logs/trace.jsonl",
            "--verbose"
        });

        assertThat(values.message()).contains("fix the test");
        assertThat(values.workspace())
                .hasValueSatisfying(path -> assertThat(path.toString()).isEqualTo("demo"));
        assertThat(values.approval()).contains(ApprovalMode.AUTO);
        assertThat(values.timeout()).contains(Duration.ofMinutes(2));
        assertThat(values.trace()).contains(CliTraceMode.JSONL);
        assertThat(values.traceFile()).contains(Path.of("logs", "trace.jsonl"));
        assertThat(values.terminal()).isFalse();
        assertThat(values.verbose()).isTrue();
    }

    @Test
    void selectsTerminalExplicitlyOrByMissingMessageAndRejectsModeConflict() {
        assertThat(CliArguments.parse(new String[] {"--terminal"}).terminal()).isTrue();
        assertThat(CliArguments.parse(new String[0]).message()).isEmpty();
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"--terminal", "-m", "task"}))
                .hasMessageContaining("--terminal cannot be used with -m/--message");
    }

    @Test
    void rejectsUnknownAndMissingOptions() {
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"--unknown"})).hasMessageContaining("unknown option");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"-m"})).hasMessageContaining("missing value");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"--trace", "unsafe"}))
                .hasMessageContaining("summary, detail, or jsonl");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"--trace-file", "trace.jsonl"}))
                .hasMessageContaining("requires --trace");
    }

    @Test
    void parsesEverySupportedTraceModeCaseInsensitively() {
        assertThat(CliArguments.parse(new String[] {"--trace", "summary"}).trace())
                .contains(CliTraceMode.SUMMARY);
        assertThat(CliArguments.parse(new String[] {"--trace", "DETAIL"}).trace())
                .contains(CliTraceMode.DETAIL);
        assertThat(CliArguments.parse(new String[] {"--trace", "JsonL"}).trace())
                .contains(CliTraceMode.JSONL);
    }

    @Test
    void parsesEveryResumeCommandForm() {
        assertThat(CliArguments.parse(new String[] {"resume"}).resume())
                .contains(new CliResumeRequest(CliResumeRequest.Target.SELECTOR, Optional.empty(), Optional.empty()));
        assertThat(CliArguments.parse(new String[] {"resume", "--last"}).resume())
                .contains(new CliResumeRequest(CliResumeRequest.Target.LAST, Optional.empty(), Optional.empty()));
        assertThat(CliArguments.parse(new String[] {"resume", "--last", "continue", "the", "work"})
                        .resume())
                .contains(new CliResumeRequest(
                        CliResumeRequest.Target.LAST, Optional.empty(), Optional.of("continue the work")));
        assertThat(CliArguments.parse(new String[] {"resume", "session-1"}).resume())
                .contains(new CliResumeRequest(
                        CliResumeRequest.Target.SESSION,
                        Optional.of(new io.haifa.agent.core.session.AgentSessionId("session-1")),
                        Optional.empty()));
        assertThat(CliArguments.parse(new String[] {"resume", "session-1", "continue", "the", "work"})
                        .resume())
                .contains(new CliResumeRequest(
                        CliResumeRequest.Target.SESSION,
                        Optional.of(new io.haifa.agent.core.session.AgentSessionId("session-1")),
                        Optional.of("continue the work")));
    }

    @Test
    void resumeAcceptsLauncherOptionsBeforeTheCommandAndPromptDelimiter() {
        CliArguments parsed = CliArguments.parse(
                new String[] {"--config", "distribution.yaml", "resume", "--last", "--", "--fix", "the", "tests"});

        assertThat(parsed.config()).contains(Path.of("distribution.yaml"));
        assertThat(parsed.resume().orElseThrow().prompt()).contains("--fix the tests");
    }

    @Test
    void rejectsAmbiguousResumeModesAndModelOverride() {
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"resume", "session-1", "--last"}))
                .hasMessageContaining("--last cannot be used with a SESSION_ID");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"resume", "--last", "--last"}))
                .hasMessageContaining("may only be specified once");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"resume", "--terminal"}))
                .hasMessageContaining("resume cannot be used");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"resume", "--model", "other"}))
                .hasMessageContaining("cannot override the Session model");
    }
}
