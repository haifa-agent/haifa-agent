package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CliArgumentsTest {
    @Test
    void parsesOneShotCommand() {
        CliArguments values = CliArguments.parse(new String[] {
            "-m", "fix the test", "--workspace", "demo", "--approval", "auto", "--timeout", "PT2M", "--verbose"
        });

        assertThat(values.message()).contains("fix the test");
        assertThat(values.workspace())
                .hasValueSatisfying(path -> assertThat(path.toString()).isEqualTo("demo"));
        assertThat(values.approval()).contains(ApprovalMode.AUTO);
        assertThat(values.timeout()).contains(Duration.ofMinutes(2));
        assertThat(values.verbose()).isTrue();
    }

    @Test
    void rejectsUnknownAndMissingOptions() {
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"--unknown"})).hasMessageContaining("unknown option");
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"-m"})).hasMessageContaining("missing value");
    }
}
