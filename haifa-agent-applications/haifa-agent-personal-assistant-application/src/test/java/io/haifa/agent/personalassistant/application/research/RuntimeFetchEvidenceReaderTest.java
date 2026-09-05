package io.haifa.agent.personalassistant.application.research;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeFetchEvidenceReaderTest {

    @Test
    void findsCompletedFetchesFromPersistence() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory(store);
        RuntimeFetchEvidenceReader reader = new RuntimeFetchEvidenceReader(ports);

        AgentRunId runId = new AgentRunId("run-task-1");
        Instant now = Instant.parse("2026-09-04T12:00:00Z");

        ToolCall fetchCall = new ToolCall(
                new ToolCallId("call-fetch-1"),
                runId,
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("p-1"),
                new RuntimeIdempotencyKey("idem-1"),
                "web_fetch",
                "1.0.0",
                new ToolArguments("web_fetch", "1.0.0", Map.of("url", "https://example.com/ethereum")),
                now);
        fetchCall.beginValidation();
        fetchCall.beginPolicyCheck();
        fetchCall.start(now);
        ToolResult fetchResult = new ToolResult(
                true,
                "Fetched untrusted external content",
                Map.of(
                        "requestedUrl", "https://example.com/ethereum",
                        "finalUrl", "https://example.com/ethereum?utm_source=test",
                        "content", "Ethereum scalability upgrade details...",
                        "contentSha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852beef",
                        "sourceAvailable", true,
                        "truncated", false),
                List.of(),
                List.of(),
                false);
        fetchCall.complete(fetchResult, now.plusSeconds(1));
        store.appendToolCall(fetchCall);

        ToolCall unrelatedCall = new ToolCall(
                new ToolCallId("call-search-1"),
                runId,
                new AgentStepId("step-2"),
                new ProviderToolCallCorrelationId("p-2"),
                new RuntimeIdempotencyKey("idem-2"),
                "web_search",
                "1.0.0",
                new ToolArguments("web_search", "1.0.0", Map.of("query", "ethereum")),
                now);
        unrelatedCall.beginValidation();
        unrelatedCall.beginPolicyCheck();
        unrelatedCall.start(now);
        unrelatedCall.complete(new ToolResult(true, "Search results", Map.of(), List.of(), List.of(), false), now);
        store.appendToolCall(unrelatedCall);

        List<ResearchFetchEvidence> completed = reader.findCompletedFetches(runId.value());

        assertThat(completed).hasSize(1);
        ResearchFetchEvidence evidence = completed.getFirst();
        assertThat(evidence.toolCallId()).isEqualTo("call-fetch-1");
        assertThat(evidence.canonicalRequestedUrl()).isEqualTo("https://example.com/ethereum");
        assertThat(evidence.canonicalFinalUrl()).contains("https://example.com/ethereum");
        assertThat(evidence.successful()).isTrue();
        assertThat(evidence.sourceAvailable()).isTrue();
        assertThat(evidence.contentSha256())
                .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852beef");
        assertThat(evidence.contentBytes()).isEqualTo("Ethereum scalability upgrade details...".length());
    }

    @Test
    void marksFailedOrUnavailableFetchProperly() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory(store);
        RuntimeFetchEvidenceReader reader = new RuntimeFetchEvidenceReader(ports);

        AgentRunId runId = new AgentRunId("run-task-2");
        Instant now = Instant.parse("2026-09-04T12:00:00Z");

        ToolCall fetchCall = new ToolCall(
                new ToolCallId("call-fetch-2"),
                runId,
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("p-3"),
                new RuntimeIdempotencyKey("idem-3"),
                "web_fetch",
                "1.0.0",
                new ToolArguments("web_fetch", "1.0.0", Map.of("url", "https://example.com/404")),
                now);
        fetchCall.beginValidation();
        fetchCall.beginPolicyCheck();
        fetchCall.start(now);
        ToolResult fetchResult = new ToolResult(
                true,
                "Unavailable",
                Map.of(
                        "requestedUrl", "https://example.com/404",
                        "finalUrl", "https://example.com/404",
                        "content", "",
                        "contentSha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        "sourceAvailable", false),
                List.of(),
                List.of(),
                false);
        fetchCall.complete(fetchResult, now);
        store.appendToolCall(fetchCall);

        List<ResearchFetchEvidence> completed = reader.findCompletedFetches(runId.value());

        assertThat(completed).hasSize(1);
        ResearchFetchEvidence evidence = completed.getFirst();
        assertThat(evidence.successful()).isFalse();
        assertThat(evidence.sourceAvailable()).isFalse();
    }
}
