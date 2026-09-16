package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompactionFileOperationsTrackerTest {

    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-file-ops");
    private static final AgentRunId RUN_ID = new AgentRunId("run-file-ops");

    @Test
    void extractsReadAndModifiedFilesDeterministically() {
        Map<ToolCallId, ToolCall> calls = new HashMap<>();

        ToolCallId call1 = new ToolCallId("call-read-1");
        calls.put(call1, createCall(call1, "file_read", Map.of("path", "src/Main.java")));

        ToolCallId call2 = new ToolCallId("call-mod-1");
        calls.put(call2, createCall(call2, "replace_file_content", Map.of("TargetFile", "src/Service.java")));

        ToolCallId call3 = new ToolCallId("call-read-2");
        calls.put(call3, createCall(call3, "workspace_file_read", Map.of("filePath", "pom.xml")));

        ToolCallId call4 = new ToolCallId("call-mod-2");
        calls.put(call4, createCall(call4, "write_to_file", Map.of("targetFile", "src/NewUtil.java")));

        // Duplicate read to verify deduplication
        ToolCallId call5 = new ToolCallId("call-read-3");
        calls.put(call5, createCall(call5, "file_read", Map.of("path", "src/Main.java")));

        AgentMessage msg1 = assistantMessage(
                "m1",
                1,
                List.of(
                        new ToolCallPart(call1, new ProviderToolCallCorrelationId("c1"), "file_read", "1.0"),
                        new ToolCallPart(
                                call2, new ProviderToolCallCorrelationId("c2"), "replace_file_content", "1.0")));

        AgentMessage msg2 = assistantMessage(
                "m2",
                2,
                List.of(
                        new ToolCallPart(call3, new ProviderToolCallCorrelationId("c3"), "workspace_file_read", "1.0"),
                        new ToolCallPart(call4, new ProviderToolCallCorrelationId("c4"), "write_to_file", "1.0"),
                        new ToolCallPart(call5, new ProviderToolCallCorrelationId("c5"), "file_read", "1.0")));

        var ops = CompactionFileOperationsTracker.track(List.of(msg1, msg2), calls::get);

        assertThat(ops.isEmpty()).isFalse();
        assertThat(ops.readFiles()).containsExactly("pom.xml", "src/Main.java");
        assertThat(ops.modifiedFiles()).containsExactly("src/NewUtil.java", "src/Service.java");

        String expectedXml = "<modified-files>\n" + "src/NewUtil.java\n"
                + "src/Service.java\n"
                + "</modified-files>\n"
                + "<read-files>\n"
                + "pom.xml\n"
                + "src/Main.java\n"
                + "</read-files>";

        assertThat(ops.toXmlTags()).isEqualTo(expectedXml);

        String baseSummary = "## Goals\n- Fix bug";
        String appended = CompactionFileOperationsTracker.appendToFileOperations(baseSummary, ops);
        assertThat(appended).isEqualTo(baseSummary + "\n\n" + expectedXml);
    }

    @Test
    void handlesEmptyOperationsGracefully() {
        var emptyOps = CompactionFileOperationsTracker.FileOperations.empty();
        assertThat(emptyOps.isEmpty()).isTrue();
        assertThat(emptyOps.toXmlTags()).isEmpty();

        String summary = "## Goals\n- Nothing modified";
        assertThat(CompactionFileOperationsTracker.appendToFileOperations(summary, emptyOps))
                .isEqualTo(summary);
        assertThat(CompactionFileOperationsTracker.appendToFileOperations(null, emptyOps))
                .isNull();
    }

    @Test
    void tracksViaRuntimeStateRepository() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();

        ToolCallId callId = new ToolCallId("store-read-1");
        ToolCall call = createCall(callId, "file_read", Map.of("path", "config.yaml"));
        store.appendToolCall(call);

        AgentMessage msg = store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("msg-store"),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(callId, new ProviderToolCallCorrelationId("c-store"), "file_read", "1.0")),
                Map.of(),
                Instant.parse("2026-07-21T00:00:00Z")));

        var ops = CompactionFileOperationsTracker.track(List.of(msg.id()), store);
        assertThat(ops.readFiles()).containsExactly("config.yaml");
        assertThat(ops.modifiedFiles()).isEmpty();
        assertThat(ops.toXmlTags()).isEqualTo("<read-files>\nconfig.yaml\n</read-files>");
    }

    private static ToolCall createCall(ToolCallId id, String toolName, Map<String, Object> args) {
        return new ToolCall(
                id,
                RUN_ID,
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("corr-" + id.value()),
                new RuntimeIdempotencyKey("idemp-" + id.value()),
                toolName,
                "1.0.0",
                new ToolArguments("schema.1", "1.0", args),
                Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static AgentMessage assistantMessage(String id, long sequence, List<ContentPart> contents) {
        return new AgentMessage(
                new AgentMessageId(id),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                sequence,
                contents,
                Map.of(),
                Instant.parse("2026-07-21T00:00:00Z").plusSeconds(sequence));
    }
}
