package io.haifa.agent.runtime.core;

import io.haifa.agent.core.content.*;
import io.haifa.agent.core.message.*;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.*;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.model.continuation.*;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.*;

/** Synthetic, immutable oracle for MS1 baseline and later retrieval/summary probes. No user data. */
final class ContextLifecycleHistoryFixture {
    static final int N = 16;
    static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    static final AgentSessionId SESSION = new AgentSessionId("session-1");
    static final AgentRunId PRIOR = new AgentRunId("baseline-prior-run");
    static final String CONSTRAINT = "Project constraint: preserve UTC epoch milliseconds.";
    static final String DECISION = "Decision: keep the full-history oracle until bounded reads are proven.";
    static final String EVIDENCE = "Historical evidence: src/clock.txt version v1 contains epoch-millis.";
    static final Map<String, String> PROBES = Map.of(
            "constraint",
            "UTC epoch milliseconds",
            "decision",
            "keep full-history oracle",
            "file",
            "src/clock.txt@v1",
            "nextStep",
            "prove bounded reads");

    private ContextLifecycleHistoryFixture() {}

    static List<AgentMessage> append(InMemoryRuntimeStore store, int turns) {
        List<AgentMessage> original = new ArrayList<>();
        original.add(store.appendSessionMessage(
                draft("constraint", MessageRole.USER, List.of(new TextPart(CONSTRAINT, "plain")))));
        original.add(store.appendSessionMessage(
                draft("decision", MessageRole.ASSISTANT, List.of(new TextPart(DECISION, "plain")))));
        original.add(store.appendSessionMessage(
                draft("evidence", MessageRole.USER, List.of(new TextPart(EVIDENCE, "plain")))));
        for (int i = 0; i < turns; i++) {
            original.add(store.appendSessionMessage(draft(
                    "user-" + i, MessageRole.USER, List.of(new TextPart("Read unrelated fixture " + i, "plain")))));
            var callId = new ToolCallId("baseline-tool-" + i);
            // Provider correlation IDs are deliberately reused by later Runs; occurrence identity is the ToolCallId.
            var correlation = new ProviderToolCallCorrelationId("provider-call-" + i);
            var call = new ToolCall(
                    callId,
                    PRIOR,
                    new AgentStepId("baseline-step-" + i),
                    correlation,
                    new RuntimeIdempotencyKey("baseline-key-" + i),
                    "file_read",
                    "1.0.0",
                    new ToolArguments("file-read", "1", Map.of("path", "unrelated/" + i)),
                    NOW);
            call.beginValidation();
            call.beginPolicyCheck();
            call.start(NOW);
            call.complete(
                    new ToolResult(
                            true,
                            "unrelated fixture",
                            Map.of("version", "v1", "body", "unrelated output ".repeat(24)),
                            List.of(),
                            List.of(),
                            false),
                    NOW);
            store.appendToolCall(call);
            var assistant = draft(
                    "assistant-" + i,
                    MessageRole.ASSISTANT,
                    List.of(new ToolCallPart(callId, correlation, "file_read", "1.0.0")));
            if (i == 0) {
                var model = DefaultResolvedModelSnapshots.deepSeekV4Pro();
                var reasoning = SensitiveModelReasoning.of("synthetic protected continuation");
                original.add(store.appendSessionMessageWithContinuation(
                        assistant,
                        new ModelContinuationDraft(
                                new ModelContinuationRef(
                                        "baseline-continuation", "1.0", reasoning.digest(), reasoning.byteLength()),
                                PRIOR,
                                SESSION,
                                "baseline-call",
                                model.providerId().value(),
                                model.providerModelId(),
                                model.configurationDigest(),
                                Set.of(correlation.value()),
                                reasoning,
                                NOW)));
            } else {
                original.add(store.appendSessionMessage(assistant));
            }
            original.add(store.appendSessionMessage(draft(
                    "result-" + i,
                    MessageRole.TOOL,
                    List.of(new ToolResultPart(callId, correlation, "unrelated fixture")))));
        }
        return List.copyOf(original);
    }

    static io.haifa.agent.model.api.AgentChatResponse summary(io.haifa.agent.model.api.AgentChatRequest request) {
        var matcher = java.util.regex.Pattern.compile("\\[msg (\\S+):")
                .matcher(request.messages().getLast().content());
        String ref = matcher.find() ? matcher.group(1) : "m001";
        Map<String, Object> value = Map.of(
                "schemaVersion",
                "v1",
                "language",
                "en",
                "goals",
                List.of(Map.of(
                        "stableItemId",
                        "g1",
                        "text",
                        "Context lifecycle baseline",
                        "confidence",
                        "OBSERVED",
                        "sourceRefs",
                        List.of(ref))),
                "constraints",
                List.of(),
                "progress",
                Map.of("completed", List.of(), "active", List.of(), "blocked", List.of()),
                "decisions",
                List.of(),
                "nextSteps",
                List.of(),
                "criticalContext",
                List.of(),
                "unresolvedQuestions",
                List.of());
        return new io.haifa.agent.model.api.AgentChatResponse(
                "summary",
                request.model().providerModelId(),
                "",
                List.of(),
                io.haifa.agent.model.api.ModelFinishReason.STOP,
                io.haifa.agent.model.api.ModelUsage.unpriced(10, 10),
                "",
                Map.of(),
                Optional.empty(),
                Optional.of(value));
    }

    private static SessionMessageDraft draft(String id, MessageRole role, List<ContentPart> contents) {
        return new SessionMessageDraft(
                new AgentMessageId("baseline-" + id),
                SESSION,
                Optional.of(PRIOR),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                contents,
                Map.of(),
                NOW);
    }
}
