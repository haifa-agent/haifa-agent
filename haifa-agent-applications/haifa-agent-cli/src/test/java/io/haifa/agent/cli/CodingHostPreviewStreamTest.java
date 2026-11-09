package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.*;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.coding.host.internal.CodingHostPreviewStream;
import org.junit.jupiter.api.Test;

class CodingHostPreviewStreamTest {
    @Test
    void boundsTheNumberOfAmbiguousToolStreams() {
        var stream = new CodingHostPreviewStream();
        for (int i = 0; i < CodingHostPreviewStream.MAXIMUM_PENDING_STREAMS; i++) {
            assertThat(stream.accept(new ToolOutputPreview(
                            new AgentRunId("run-stream-limit"),
                            new ToolCallId("call-" + i),
                            ExecutionOutputChannel.STDOUT,
                            "B",
                            false,
                            false)))
                    .isNull();
        }
        assertThatThrownBy(() -> stream.accept(new ToolOutputPreview(
                        new AgentRunId("run-stream-limit"),
                        new ToolCallId("excess-call"),
                        ExecutionOutputChannel.STDOUT,
                        "B",
                        false,
                        false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void displayControlsAndCredentialPrefixesSurviveFrameBoundariesWithChannelIsolation() {
        var stream = new CodingHostPreviewStream();
        assertThat(stream.accept(preview("Bearer \u001b[3", ExecutionOutputChannel.STDOUT, false)))
                .isNull();
        assertThat(stream.accept(preview("independent progress\n", ExecutionOutputChannel.STDERR, false))
                        .text())
                .isEqualTo("independent progress\n");
        assertThat(stream.accept(preview("2msynthetic-color-token\u001b[0m ", ExecutionOutputChannel.STDOUT, false))
                        .text())
                .isEqualTo("Bearer [REDACTED] ");
        assertThat(stream.accept(preview(
                                "\u001b]0;private title\u0007safe progress\n", ExecutionOutputChannel.STDOUT, false))
                        .text())
                .isEqualTo("safe progress\n");
    }

    @Test
    void unknownContinuityAndExcessiveAmbiguityNeverForwardAnUnsafeSuffix() {
        var stream = new CodingHostPreviewStream();
        assertThatThrownBy(() -> stream.accept(preview("possibly unsafe suffix", ExecutionOutputChannel.STDOUT, true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stream.accept(preview("Bearer ", ExecutionOutputChannel.STDOUT, false)))
                .isNull();
        assertThatThrownBy(() -> stream.accept(preview("x".repeat(5000), ExecutionOutputChannel.STDOUT, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ToolOutputPreview preview(String text, ExecutionOutputChannel channel, boolean dropped) {
        return new ToolOutputPreview(
                new AgentRunId("run-preview-state"),
                new ToolCallId("call-preview-state"),
                channel,
                text,
                false,
                dropped);
    }
}
