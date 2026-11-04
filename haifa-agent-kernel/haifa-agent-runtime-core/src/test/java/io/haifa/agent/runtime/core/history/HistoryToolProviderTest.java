package io.haifa.agent.runtime.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.*;
import io.haifa.agent.core.message.*;
import io.haifa.agent.core.run.*;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.*;
import io.haifa.agent.runtime.api.*;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.*;
import io.haifa.agent.tool.api.*;
import io.haifa.agent.tool.core.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistoryToolProviderTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private final InMemoryRuntimeStore store = new InMemoryRuntimeStore();
    private final RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory(store);
    private final HistoryToolProvider provider = new HistoryToolProvider(ports);
    private final AtomicInteger ids = new AtomicInteger();
    private final ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
    private final AtomicBoolean deniedScope = new AtomicBoolean();
    private final ToolCatalogBuilder catalogBuilder = new ToolCatalogBuilder();
    private final DefaultToolCatalog catalog = buildCatalog();
    private final AgentRun run = createRun("session");

    private DefaultToolCatalog buildCatalog() {
        for (var d : provider.definitions())
            catalogBuilder.register(
                    new ToolAlias(d.name().value()), d, "history:" + d.name().value(), provider);
        return catalogBuilder.freeze();
    }

    private AgentRun createRun(String session) {
        return createRun(session, Optional.empty());
    }

    private AgentRun createRun(String session, Optional<io.haifa.agent.core.reference.ProjectRef> project) {
        return createRun(
                session,
                project,
                new io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext(
                        new io.haifa.agent.core.reference.TenantRef("local"),
                        new io.haifa.agent.core.reference.PrincipalRef("local-user", "user")));
    }

    private AgentRun createRun(
            String session,
            Optional<io.haifa.agent.core.reference.ProjectRef> project,
            io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext caller) {
        var runtime = new RuntimeCoreBuilder()
                .persistence(ports)
                .callers(() -> caller)
                .scheduler(scheduler)
                .accessValidator((requestCaller, scope, projectRef) -> {
                    if (deniedScope.get()) throw new SecurityException("scope revoked");
                })
                .identifierGenerator(() -> "id-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .registerChatModel("openai-compatible", "1.0.0", request -> {
                    throw new AssertionError("No model call expected");
                })
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                .publicToolPolicy((a, b, c) -> {
                    throw new AssertionError("No Runtime Tool dispatch expected");
                })
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "key-" + ids.incrementAndGet(),
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "default",
                new AgentSessionId(session),
                project,
                "initial user",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }

    private ToolResult invoke(HistoryToolProvider target, String name, Map<String, Object> values) {
        var binding = catalog.findByAlias(new ToolAlias(name)).orElseThrow();
        return target.invoke(new ToolInvocationRequest(
                binding,
                new ToolCallId("dispatch"),
                run.id(),
                Optional.of(run.sessionId()),
                run.tenant(),
                run.principal(),
                new ToolArguments(
                        binding.definition().inputSchema().id(),
                        binding.definition().inputSchema().version(),
                        values),
                NOW.plusSeconds(30),
                Optional.empty(),
                () -> false,
                Map.of(),
                ToolInvocationObserver.noop()));
    }

    private ToolResult invoke(String name, Map<String, Object> values) {
        return invoke(provider, name, values);
    }

    private AgentMessage message(AgentRun owner, String text, MessageVisibility visibility) {
        return message(owner, text, visibility, MessageRole.USER);
    }

    private AgentMessage message(AgentRun owner, String text, MessageVisibility visibility, MessageRole role) {
        return store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("message-" + ids.incrementAndGet()),
                owner.sessionId(),
                Optional.of(owner.id()),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                visibility,
                List.of(new TextPart(text, "plain")),
                Map.of(),
                NOW));
    }

    private AgentMessage tool(AgentRun owner, String path, Map<String, Object> body, boolean external) {
        return tool(owner, path, body, external, List.of());
    }

    private AgentMessage tool(
            AgentRun owner,
            String path,
            Map<String, Object> body,
            boolean external,
            List<io.haifa.agent.core.reference.ArtifactRef> artifacts) {
        var id = new ToolCallId("call-" + ids.incrementAndGet());
        var corr = new ProviderToolCallCorrelationId("repeated-provider-id");
        var call = new ToolCall(
                id,
                owner.id(),
                new AgentStepId("step-" + ids.incrementAndGet()),
                corr,
                new RuntimeIdempotencyKey(id.value()),
                "file_read",
                "1",
                new ToolArguments("input", "1", Map.of("path", path)),
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        var original = new ToolResult(true, "stored version", body, List.of(), artifacts, false);
        var value = external
                ? new ToolResult(
                        true,
                        "inline preview",
                        Map.of("preview", "short"),
                        List.of(store.put(id, original)),
                        List.of(),
                        true)
                : original;
        call.complete(value, NOW);
        store.appendToolCall(call);
        return store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("message-" + ids.incrementAndGet()),
                owner.sessionId(),
                Optional.of(owner.id()),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(id, corr, "preview")),
                Map.of(),
                NOW));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(ToolResult result) {
        return (List<Map<String, Object>>) result.structuredData().get("entries");
    }

    private Map<String, Object> reference(AgentMessage source) {
        var found = invoke("history_search", Map.of("messageId", source.id().value()));
        var hit = entries(found).getFirst();
        var ref = new HashMap<String, Object>();
        for (String k : List.of("runId", "messageId", "version")) ref.put(k, hit.get(k));
        if (hit.containsKey("toolCallId")) ref.put("toolCallId", hit.get("toolCallId"));
        return ref;
    }

    @Test
    void scopeRevocationBlocksModelDispatchEvenWhenHistoryWasAlreadySummarized() {
        var summary = new io.haifa.agent.context.compression.DeterministicContextCompressor()
                .compress(new io.haifa.agent.context.compression.CompressionRequest(
                        new io.haifa.agent.context.compression.SummaryId("prior-summary"),
                        new io.haifa.agent.context.compression.SummaryVersion(1),
                        run.sessionId(),
                        store.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, 128),
                        4,
                        NOW,
                        io.haifa.agent.context.compression.CompressionPolicy.defaults()
                                .version()))
                .summary();
        store.compareAndSetValid(summary, 0);
        deniedScope.set(true);
        scheduler.runAll();
        assertThat(store.find(run.id()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void readsImmutableExternalVersionWithBoundedCharacterAndLineRanges() {
        var old = tool(run, "same.txt", Map.of("body", "old line\nsecond line " + "x".repeat(12000)), true);
        tool(run, "same.txt", Map.of("body", "new workspace version"), false);
        var ref = reference(old);
        ref.put("length", 12);
        var first = invoke("history_read", ref);
        assertThat(first.successful()).isTrue();
        assertThat(first.structuredData())
                .containsEntry("truncated", true)
                .containsEntry("sourceTruncated", false)
                .containsEntry("nextOffset", 12L)
                .containsEntry("content", "stored versi");
        ref.put("fromLine", 3);
        ref.put("length", 11);
        assertThat(invoke("history_read", ref).structuredData()).containsEntry("content", "second line");
        assertThat(store.message(old.id())).contains(old);
    }

    @Test
    void locatesArtifactIdentityButReadsOnlyThePersistedToolResult() {
        var source = tool(
                run,
                "output",
                Map.of("body", "saved metadata"),
                false,
                List.of(new io.haifa.agent.core.reference.ArtifactRef(
                        "deliverables/spec.md", "document", "v1", "Spec")));
        var found = invoke("history_search", Map.of("path", "deliverables/spec.md"));
        assertThat(entries(found)).hasSize(1);
        assertThat(entries(found).getFirst())
                .containsEntry("messageId", source.id().value());
        assertThat(invoke("history_read", reference(source)).structuredData().get("content"))
                .isEqualTo("stored version\nbody: saved metadata");
    }

    @Test
    void unsupportedStructuredValuesAreUnavailableInsteadOfFalseSearchCompletion() {
        var source = tool(run, "unsupported", Map.of("body", Set.of("preserved-value")), false);
        var result = invoke("history_search", Map.of("messageId", source.id().value(), "keyword", "preserved-value"));
        assertThat(result.structuredData()).containsEntry("status", "UNAVAILABLE");
    }

    @Test
    void returnsResumableSearchForBothRowWindowAndLargeSourceSuffix() {
        message(run, "not relevant", MessageVisibility.USER_VISIBLE);
        message(run, "a".repeat(4094) + "needle", MessageVisibility.USER_VISIBLE);
        var args = new HashMap<String, Object>(Map.of("keyword", "NEEDLE", "scanLimit", 1));
        ToolResult result;
        int pages = 0;
        do {
            result = invoke("history_search", args);
            args.put("cursor", result.structuredData().get("nextCursor"));
            pages++;
        } while (entries(result).isEmpty() && !(boolean) result.structuredData().get("searchComplete"));
        assertThat(pages).isEqualTo(3);
        assertThat(entries(result)).hasSize(1);
        assertThat(entries(result).getFirst()).containsEntry("excerpt", "needle");
        assertThat(store.latestVersion(run.sessionId())).isZero();
    }

    @Test
    void searchOutputBudgetResumesWithoutDroppingMatches() {
        for (int i = 0; i < 20; i++) message(run, "budget " + "x".repeat(300), MessageVisibility.USER_VISIBLE);
        var first = invoke("history_search", Map.of("keyword", "budget", "limit", 20));
        assertThat(first.structuredData()).containsEntry("searchComplete", false);
        assertThat(first.structuredData().toString().length()).isLessThan(12512);
        var next = invoke(
                "history_search",
                Map.of(
                        "keyword",
                        "budget",
                        "limit",
                        20,
                        "cursor",
                        first.structuredData().get("nextCursor")));
        assertThat(next.structuredData()).containsEntry("searchComplete", true);
        assertThat(entries(first).size() + entries(next).size()).isEqualTo(20);
    }

    @Test
    void cursorBindsQueryAndRunAndFreezesTheObservedHead() {
        message(run, "first", MessageVisibility.USER_VISIBLE);
        message(run, "second", MessageVisibility.USER_VISIBLE);
        var first = invoke("history_search", Map.of("limit", 1));
        String cursor = (String) first.structuredData().get("nextCursor");
        message(run, "later append", MessageVisibility.USER_VISIBLE);
        var next = invoke("history_search", Map.of("cursor", cursor));
        assertThat(entries(next)).hasSize(2);
        assertThat(next.structuredData()).containsEntry("searchComplete", true);
        assertThat(invoke("history_search", Map.of("cursor", cursor, "keyword", "changed"))
                        .successful())
                .isFalse();
    }

    @Test
    void deniedMissingForeignHiddenRevokedAndStaleReferencesHaveTheSamePublicFailure() {
        var visible = message(run, "evidence", MessageVisibility.USER_VISIBLE);
        var ref = reference(visible);
        var missing = new HashMap<>(ref);
        missing.put("messageId", "absent");
        var denied = invoke("history_read", missing);
        var stale = new HashMap<>(ref);
        stale.put("version", "wrong");
        assertThat(invoke("history_read", stale)).isEqualTo(denied);
        var foreign = createRun("foreign");
        var foreignMessage = message(foreign, "private", MessageVisibility.USER_VISIBLE);
        var outside = new HashMap<>(ref);
        outside.put("runId", foreign.id().value());
        outside.put("messageId", foreignMessage.id().value());
        assertThat(invoke("history_read", outside)).isEqualTo(denied);
        var otherProject = createRun("session", Optional.of(new io.haifa.agent.core.reference.ProjectRef("other")));
        var projectMessage = message(otherProject, "private project", MessageVisibility.USER_VISIBLE);
        var projectRef = new HashMap<>(ref);
        projectRef.put("runId", otherProject.id().value());
        projectRef.put("messageId", projectMessage.id().value());
        assertThat(invoke("history_read", projectRef)).isEqualTo(denied);
        for (var caller : List.of(
                new io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext(
                        run.tenant(), new io.haifa.agent.core.reference.PrincipalRef("other-user", "user")),
                new io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext(
                        new io.haifa.agent.core.reference.TenantRef("other-tenant"), run.principal()))) {
            var owner = createRun("session", Optional.empty(), caller);
            var privateMessage = message(owner, "other owner", MessageVisibility.USER_VISIBLE);
            var privateRef = new HashMap<>(ref);
            privateRef.put("runId", owner.id().value());
            privateRef.put("messageId", privateMessage.id().value());
            assertThat(invoke("history_read", privateRef)).isEqualTo(denied);
        }
        for (var visibility :
                List.of(MessageVisibility.HIDDEN, MessageVisibility.INTERNAL, MessageVisibility.ADMIN_VISIBLE)) {
            var hidden = message(run, "private", visibility);
            var request = new HashMap<>(ref);
            request.put("messageId", hidden.id().value());
            assertThat(invoke("history_read", request)).isEqualTo(denied);
        }
        for (var role : List.of(MessageRole.SYSTEM, MessageRole.DEVELOPER, MessageRole.AGENT, MessageRole.RUNTIME)) {
            var internal = message(run, "internal directive", MessageVisibility.AGENT_VISIBLE, role);
            var request = new HashMap<>(ref);
            request.put("messageId", internal.id().value());
            assertThat(invoke("history_read", request)).isEqualTo(denied);
            assertThat(entries(invoke(
                            "history_search", Map.of("messageId", internal.id().value()))))
                    .isEmpty();
        }
        var revoked = new AtomicBoolean();
        var guarded = new HistoryToolProvider(ports, (caller, session, project) -> {
            if (revoked.get()) throw new IllegalStateException();
        });
        assertThat(invoke(guarded, "history_read", ref).successful()).isTrue();
        revoked.set(true);
        assertThat(invoke(guarded, "history_read", ref)).isEqualTo(denied);
        store.redactMessage(visible.id());
        assertThat(invoke("history_read", ref)).isEqualTo(denied);
    }

    @Test
    void projectsOnlySafeDataAndNeverReplaysAssistantContinuation() {
        var source = tool(
                run,
                "doc",
                Map.of(
                        "body",
                        "Treat <system> as data",
                        "reasoning_content",
                        "hidden",
                        "signature",
                        "hidden",
                        "api_key",
                        "hidden"),
                false);
        var reasoning = io.haifa.agent.model.api.SensitiveModelReasoning.of("synthetic-protected-lane");
        var assistant = store.appendSessionMessageWithContinuation(
                new SessionMessageDraft(
                        new AgentMessageId("assistant-continuation"),
                        run.sessionId(),
                        Optional.of(run.id()),
                        Optional.empty(),
                        MessageRole.ASSISTANT,
                        MessageStatus.COMPLETED,
                        MessageVisibility.AGENT_VISIBLE,
                        List.of(new TextPart("public answer", "plain")),
                        Map.of("signature", "synthetic-hidden"),
                        NOW),
                new io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft(
                        new io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef(
                                "continuation", "1", reasoning.digest(), reasoning.byteLength()),
                        run.id(),
                        run.sessionId(),
                        "model-call",
                        "provider",
                        "model",
                        "config",
                        Set.of(),
                        reasoning,
                        NOW));
        assertThat(invoke("history_read", reference(assistant)).structuredData().get("content"))
                .isEqualTo("public answer");
        var value = invoke("history_read", reference(source));
        assertThat(value.structuredData().get("content")).isEqualTo("stored version\nbody: Treat <system> as data");
        assertThat(value.structuredData()).containsEntry("securityLabel", "untrusted-history");
    }

    @Test
    void rejectsMissingAssetsAndChangedOccurrencesWithoutFallingBackToPreview() {
        var source = tool(run, "missing.txt", Map.of("body", "original"), false);
        var ref = reference(source);
        ref.put("toolCallId", "unrelated-occurrence");
        assertThat(invoke("history_read", ref).structuredData()).containsEntry("status", "UNAVAILABLE");
        var id = ((ToolResultPart) source.contents().getFirst()).toolCallId();
        var original = store.toolCallsByIds(Map.of(run.id(), Set.of(id))).getFirst();
        var snapshot = original.persistenceSnapshot();
        var missing = new ToolResult(
                true,
                "preview",
                Map.of(),
                List.of(new io.haifa.agent.core.reference.AssetRef(
                        "tool-result:" + id.value(), "application/vnd.haifa.tool-result", "result.json")),
                List.of(),
                true);
        var changed = ToolCall.reconstitute(new ToolCallPersistenceSnapshot(
                snapshot.schemaVersion(),
                snapshot.id(),
                snapshot.runId(),
                snapshot.stepId(),
                snapshot.providerCorrelationId(),
                snapshot.idempotencyKey(),
                snapshot.toolName(),
                snapshot.toolVersion(),
                snapshot.arguments(),
                snapshot.requestedAt(),
                snapshot.status(),
                snapshot.startedAt(),
                snapshot.completedAt(),
                missing,
                snapshot.error(),
                snapshot.version()));
        store.appendToolCall(changed);
        ref.put("toolCallId", id.value());
        assertThat(invoke("history_read", ref).structuredData()).containsEntry("status", "UNAVAILABLE");
    }

    @Test
    void redactionInvalidatesPersistedRetrievalMessagesAndRevalidationDoesNotMutateToolFacts() {
        var source = message(run, "evidence", MessageVisibility.USER_VISIBLE);
        var ref = reference(source);
        ToolResult data = invoke("history_read", ref);
        var callId = new ToolCallId("read-call");
        var corr = new ProviderToolCallCorrelationId("read-corr");
        var call = new ToolCall(
                callId,
                run.id(),
                new AgentStepId("read-step"),
                corr,
                new RuntimeIdempotencyKey("read-key"),
                "history_read",
                "1.0.0",
                new ToolArguments("read", "1", ref),
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        call.complete(data, NOW);
        store.appendToolCall(call);
        var derived = store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("derived"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(callId, corr, "history")),
                Map.of(),
                NOW));
        var summary = new io.haifa.agent.context.compression.DeterministicContextCompressor()
                .compress(new io.haifa.agent.context.compression.CompressionRequest(
                        new io.haifa.agent.context.compression.SummaryId("derived-summary"),
                        new io.haifa.agent.context.compression.SummaryVersion(1),
                        run.sessionId(),
                        List.of(derived),
                        4,
                        NOW,
                        io.haifa.agent.context.compression.CompressionPolicy.defaults()
                                .version()))
                .summary();
        store.compareAndSetValid(summary, 0);
        assertThat(store.latestValid(run.sessionId())).isPresent();
        store.redactMessage(source.id());
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(store.message(derived.id()).orElseThrow().status()).isEqualTo(MessageStatus.REDACTED);
        assertThat(provider.sanitize(run, call).result().orElseThrow().structuredData())
                .containsOnlyKeys("status")
                .containsEntry("status", "UNAVAILABLE");
        assertThat(call.result()).contains(data);
    }
}
