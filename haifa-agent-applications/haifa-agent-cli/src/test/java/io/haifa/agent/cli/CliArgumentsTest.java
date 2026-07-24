package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
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
        assertThat(values.verbose()).isTrue();
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
}
