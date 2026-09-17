package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
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
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
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

    @Test
    void truncatesWhenExceedingMaxFilesPerTag() {
        java.util.Set<String> files = new java.util.TreeSet<>();
        for (int i = 1; i <= 60; i++) {
            files.add(String.format("file-%03d.txt", i));
        }
        var ops = new CompactionFileOperationsTracker.FileOperations(files, files);
        String xml = ops.toXmlTags();

        assertThat(xml).contains("<modified-files>");
        assertThat(xml).contains("<!-- ... 10 more modified files omitted -->");
        assertThat(xml).contains("<read-files>");
        assertThat(xml).contains("<!-- ... 10 more read files omitted -->");
        assertThat(xml).contains("file-050.txt");
        assertThat(xml).doesNotContain("file-051.txt\n");
    }

    @Test
    void matchesNamespacedToolNames() {
        Map<ToolCallId, ToolCall> calls = new HashMap<>();
        ToolCallId call1 = new ToolCallId("ns-read");
        calls.put(call1, createCall(call1, "mcp__server__file_read", Map.of("path", "server_config.json")));

        ToolCallId call2 = new ToolCallId("ns-mod");
        calls.put(call2, createCall(call2, "ide:write_to_file", Map.of("filePath", "app.py")));

        AgentMessage msg = assistantMessage(
                "m-ns",
                1,
                List.of(
                        new ToolCallPart(
                                call1, new ProviderToolCallCorrelationId("c-ns1"), "mcp__server__file_read", "1.0"),
                        new ToolCallPart(
                                call2, new ProviderToolCallCorrelationId("c-ns2"), "ide:write_to_file", "1.0")));

        var ops = CompactionFileOperationsTracker.track(List.of(msg), calls::get);
        assertThat(ops.readFiles()).containsExactly("server_config.json");
        assertThat(ops.modifiedFiles()).containsExactly("app.py");
    }

    @Test
    void ignoresFailedOrUnfinishedToolCalls() {
        Map<ToolCallId, ToolCall> calls = new HashMap<>();

        // Successful read -> should be recorded
        ToolCallId okRead = new ToolCallId("ok-read");
        calls.put(okRead, createCall(okRead, "file_read", Map.of("path", "src/SuccessRead.java"), true, true));

        // Failed read -> should NOT be recorded
        ToolCallId failedRead = new ToolCallId("failed-read");
        calls.put(failedRead, createCall(failedRead, "file_read", Map.of("path", "src/FailedRead.java"), true, false));

        // Unfinished read -> should NOT be recorded
        ToolCallId unfinishedRead = new ToolCallId("unfin-read");
        calls.put(
                unfinishedRead,
                createCall(unfinishedRead, "file_read", Map.of("path", "src/UnfinishedRead.java"), false, false));

        // Successful mutation -> should be recorded
        ToolCallId okMod = new ToolCallId("ok-mod");
        calls.put(okMod, createCall(okMod, "write_to_file", Map.of("TargetFile", "src/SuccessMod.java"), true, true));

        // Failed mutation -> should NOT be recorded
        ToolCallId failedMod = new ToolCallId("failed-mod");
        calls.put(
                failedMod,
                createCall(failedMod, "replace_file_content", Map.of("TargetFile", "src/FailedMod.java"), true, false));

        // Unfinished mutation -> should NOT be recorded
        ToolCallId unfinishedMod = new ToolCallId("unfin-mod");
        calls.put(
                unfinishedMod,
                createCall(unfinishedMod, "apply_patch", Map.of("TargetFile", "src/UnfinishedMod.java"), false, false));

        AgentMessage msg = assistantMessage(
                "msg-mixed",
                1,
                List.of(
                        new ToolCallPart(okRead, new ProviderToolCallCorrelationId("c1"), "file_read", "1.0"),
                        new ToolCallPart(failedRead, new ProviderToolCallCorrelationId("c2"), "file_read", "1.0"),
                        new ToolCallPart(unfinishedRead, new ProviderToolCallCorrelationId("c3"), "file_read", "1.0"),
                        new ToolCallPart(okMod, new ProviderToolCallCorrelationId("c4"), "write_to_file", "1.0"),
                        new ToolCallPart(
                                failedMod, new ProviderToolCallCorrelationId("c5"), "replace_file_content", "1.0"),
                        new ToolCallPart(
                                unfinishedMod, new ProviderToolCallCorrelationId("c6"), "apply_patch", "1.0")));

        var ops = CompactionFileOperationsTracker.track(List.of(msg), calls::get);
        assertThat(ops.readFiles()).containsExactly("src/SuccessRead.java");
        assertThat(ops.modifiedFiles()).containsExactly("src/SuccessMod.java");
    }

    private static ToolCall createCall(ToolCallId id, String toolName, Map<String, Object> args) {
        return createCall(id, toolName, args, true, true);
    }

    private static ToolCall createCall(
            ToolCallId id, String toolName, Map<String, Object> args, boolean completed, boolean successful) {
        var call = new ToolCall(
                id,
                RUN_ID,
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("corr-" + id.value()),
                new RuntimeIdempotencyKey("idemp-" + id.value()),
                toolName,
                "1.0.0",
                new ToolArguments("schema.1", "1.0", args),
                Instant.parse("2026-07-21T00:00:00Z"));
        if (!completed) {
            return call;
        }
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(Instant.parse("2026-07-21T00:00:01Z"));
        if (successful) {
            call.complete(
                    new ToolResult(true, "ok", Map.of(), List.of(), List.of(), false),
                    Instant.parse("2026-07-21T00:00:02Z"));
        } else {
            call.fail(
                    new ToolExecutionError(new AgentError(
                            AgentErrorCode.TOOL_INVOCATION_FAILED,
                            Map.of(),
                            "tool failed",
                            Instant.parse("2026-07-21T00:00:02Z"))),
                    new ToolResult(false, "failed", Map.of(), List.of(), List.of(), false),
                    Instant.parse("2026-07-21T00:00:02Z"));
        }
        return call;
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
