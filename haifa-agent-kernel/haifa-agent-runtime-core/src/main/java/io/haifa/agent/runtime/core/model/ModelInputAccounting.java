package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Internal, aggregate-only evidence for actual ordinary and summary requests. Never reads reasoning bodies. */
public final class ModelInputAccounting {
    private static final Set<String> SOURCES = Set.of(
            "fixedInstructions",
            "currentRunUser",
            "currentRunAssistant",
            "currentRunTool",
            "priorRunMessages",
            "priorRunTool",
            "summary",
            "memory",
            "runtimeState",
            "summarySource",
            "protocolProjection");

    private ModelInputAccounting() {}

    static Map<String, Long> sources(List<ModelMessage> messages, Map<ModelMessage, String> origins) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (ModelMessage message : messages) {
            String source = origins.getOrDefault(message, "protocolProjection");
            if (!SOURCES.contains(source)) throw new IllegalArgumentException("unsupported accounting source");
            result.merge(source, visibleTokens(message), Long::sum);
        }
        return Map.copyOf(result);
    }

    static long visibleTokens(ModelMessage message) {
        long tokens = message.content().isEmpty() ? 0L : HeuristicTokenEstimator.tokens(message.content());
        for (var call : message.toolCalls()) {
            tokens += HeuristicTokenEstimator.tokens(call.name())
                    + HeuristicTokenEstimator.tokens(call.arguments())
                    + HeuristicTokenEstimator.tokens(
                            call.providerCorrelationId().value())
                    + 12L;
        }
        if (!message.toolResultData().isEmpty()) tokens += HeuristicTokenEstimator.tokens(message.toolResultData());
        if (message.providerCorrelationId().isPresent()) {
            tokens += HeuristicTokenEstimator.tokens(
                            message.providerCorrelationId().orElseThrow().value())
                    + 12L;
        }
        return tokens;
    }

    public static Map<String, Object> estimate(AgentChatRequest request, Map<String, Long> sources) {
        Map<String, Long> bounded = new LinkedHashMap<>();
        sources.forEach((source, value) -> {
            if (!SOURCES.contains(source) || value < 0) throw new IllegalArgumentException("invalid accounting source");
            bounded.put(source, value);
        });
        long visible = request.messages().stream()
                .mapToLong(ModelInputAccounting::visibleTokens)
                .sum();
        if (bounded.values().stream().mapToLong(Long::longValue).sum() != visible) {
            throw new IllegalArgumentException("accounting does not match the assembled request");
        }
        var estimator = new HeuristicTokenEstimator();
        long toolSchemas =
                request.tools().stream().mapToLong(estimator::estimate).sum();
        long responseSchema = request.structuredOutput()
                .map(schema -> (long) HeuristicTokenEstimator.tokens(schema.jsonSchema()))
                .orElse(0L);
        long wrappers = request.messages().size() * 6L;
        Set<String> unknown = new TreeSet<>();
        unknown.add("providerFormatting");
        if (!request.options().isEmpty()) unknown.add("providerOptions");
        long protectedMessages = request.messages().stream()
                .filter(message -> message.reasoning().isPresent())
                .count();
        long mediaMessages = request.messages().stream()
                .filter(message ->
                        !message.images().isEmpty() || !message.audios().isEmpty())
                .count();
        if (protectedMessages > 0) unknown.add("protectedContinuation");
        if (mediaMessages > 0) unknown.add("multimodal");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountingVersion", "model-input-v1");
        result.put("sourceEstimatedTokens", Map.copyOf(bounded));
        result.put("toolSchemaEstimatedTokens", toolSchemas);
        result.put("responseSchemaEstimatedTokens", responseSchema);
        result.put("protocolEstimatedTokens", wrappers);
        result.put("modelBoundVisibleEstimatedTokens", visible + toolSchemas + responseSchema + wrappers);
        result.put("unknownComponents", List.copyOf(unknown));
        result.put("protectedMessageCount", protectedMessages);
        result.put("multimodalMessageCount", mediaMessages);
        result.put("wireMessageCount", request.messages().size());
        return Map.copyOf(result);
    }

    public static void record(
            RuntimeEventAppender events,
            TimeProvider time,
            FrozenModelBinding binding,
            AgentChatRequest request,
            String kind,
            Map<String, Long> sources,
            Map<String, Object> selection) {
        if (events == null
                || !RuntimeControlOptions.inputAccountingEnabled(
                        binding.configuration().modelRequestOptions())) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>(estimate(request, sources));
            data.putAll(selection);
            data.putAll(prefix(request));
            data.put("callKind", kind);
            data.put("modelCallId", request.callId().value());
            data.put("modelRequestId", request.requestId().value());
            data.put("attempt", request.attempt());
            data.put("providerId", request.model().providerId().value());
            data.put("providerModelId", request.model().providerModelId());
            data.put("apiStyle", request.model().apiStyle().value());
            data.put("dialect", request.model().dialect());
            data.put("modelConfigurationDigest", request.model().configurationDigest());
            data.put(
                    "runConfigurationDigest",
                    binding.configuration().reference().contentHash());
            data.put("requestStartedAtEpochMillis", time.now().toEpochMilli());
            events.append(request.runId(), "model.input-accounted", data, time.now());
        } catch (RuntimeException diagnosticUnavailable) {
            // Optional diagnostics never alter request admission, content, or recovery.
        }
    }

    /** A visible fixed-prefix identity, not a claim about Provider cache reuse or protected state. */
    public static Map<String, Object> prefix(AgentChatRequest request) {
        var fixed = request.messages().stream()
                .takeWhile(message -> message.role() == io.haifa.agent.model.api.ModelMessageRole.SYSTEM
                        || message.role() == io.haifa.agent.model.api.ModelMessageRole.DEVELOPER)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prefixIdentityVersion", "visible-fixed-prefix-v1");
        result.put("previousReusableRequestStartedAt", "UNKNOWN");
        result.put("expectedCacheReadTokens", "UNKNOWN");
        result.put("expectedCacheWriteTokens", "UNKNOWN");
        result.put("expectedUncachedTailTokens", "UNKNOWN");
        result.put("cacheConfiguration", "BINDING_DEPENDENT_UNVERIFIED");
        if (fixed.stream()
                .anyMatch(message -> message.reasoning().isPresent()
                        || !message.images().isEmpty()
                        || !message.audios().isEmpty())) {
            result.put("visibleFixedPrefixIdentity", "UNKNOWN");
            return Map.copyOf(result);
        }
        result.put(
                "visibleFixedPrefixIdentity",
                prefixKey(
                        request.model().configurationDigest(),
                        fixed.stream()
                                .map(message -> Map.of("role", message.role().name(), "text", message.content()))
                                .toList(),
                        request.tools(),
                        request.structuredOutput()
                                .map(schema -> schema.jsonSchema())
                                .orElse(Map.of())));
        result.put(
                "visibleFixedPrefixEstimatedTokens",
                fixed.stream().mapToLong(ModelInputAccounting::visibleTokens).sum()
                        + request.tools().stream()
                                .mapToLong(new HeuristicTokenEstimator()::estimate)
                                .sum());
        return Map.copyOf(result);
    }

    public static String expectedPrefix(
            FrozenModelBinding binding,
            List<io.haifa.agent.context.prompt.PromptComponent> prompts,
            List<io.haifa.agent.model.api.ModelToolSpecification> tools) {
        return prefixKey(
                binding.configuration().model().configurationDigest(),
                prompts.stream()
                        .map(prompt -> Map.of("role", "SYSTEM", "text", prompt.text()))
                        .toList(),
                tools,
                binding.configuration()
                        .structuredOutput()
                        .map(schema -> schema.jsonSchema())
                        .orElse(Map.of()));
    }

    private static String prefixKey(
            String model,
            List<Map<String, String>> instructions,
            List<io.haifa.agent.model.api.ModelToolSpecification> tools,
            Map<String, Object> responseSchema) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("model", model);
        identity.put("instructions", instructions);
        identity.put(
                "tools",
                tools.stream()
                        .map(tool -> Map.of(
                                "name",
                                tool.name(),
                                "version",
                                tool.version(),
                                "description",
                                tool.description(),
                                "schema",
                                tool.inputJsonSchema(),
                                "strict",
                                tool.strict()))
                        .toList());
        identity.put("responseSchema", responseSchema);
        return io.haifa.agent.tool.api.ToolArgumentsDigest.sha256(
                new io.haifa.agent.core.tool.ToolArguments("model-prefix", "1", identity));
    }

    /** Exactly one response aggregate per physical call; streaming usage deltas never enter this path. */
    public static void recordUsage(
            RuntimeEventAppender events,
            TimeProvider time,
            FrozenModelBinding binding,
            AgentChatRequest request,
            String kind,
            io.haifa.agent.model.api.ModelUsage usage) {
        if (events == null
                || usage == null
                || !RuntimeControlOptions.inputAccountingEnabled(
                        binding.configuration().modelRequestOptions())) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("modelCallId", request.callId().value());
            data.put("modelRequestId", request.requestId().value());
            data.put("callKind", kind);
            data.put("attempt", request.attempt());
            data.put("inputTokens", usage.inputTokensKnown() ? usage.inputTokens() : "UNKNOWN");
            data.put("cacheReadTokens", usage.cacheHitTokensKnown() ? usage.cacheHitTokens() : "UNKNOWN");
            data.put("uncachedInputTokens", usage.cacheMissTokensKnown() ? usage.cacheMissTokens() : "UNKNOWN");
            data.put(
                    "cacheWriteTokens",
                    usage.cacheWriteTokens().isPresent()
                            ? usage.cacheWriteTokens().getAsLong()
                            : "UNKNOWN");
            data.put("outputTokens", usage.outputTokens());
            data.put("providerCostMinorUnits", usage.costKnown() ? usage.costMinorUnits() : "UNAVAILABLE");
            data.put("costWeightedInput", "UNAVAILABLE");
            RuntimeControlOptions.inputPriceRatios(binding.configuration().modelRequestOptions())
                    .filter(prices -> prices.get("modelConfigurationDigest")
                            .equals(request.model().configurationDigest()))
                    .ifPresent(prices -> usage.costWeightedInput(
                                    (java.math.BigDecimal) prices.get("uncached"),
                                    (java.math.BigDecimal) prices.get("cacheRead"),
                                    (java.math.BigDecimal) prices.get("cacheWrite"))
                            .ifPresent(weighted -> {
                                data.put("costWeightedInput", weighted.toPlainString());
                                data.put("priceSource", prices.get("source"));
                                data.put("priceDate", prices.get("date"));
                            }));
            events.append(request.runId(), "model.usage-accounted", data, time.now());
        } catch (RuntimeException diagnosticUnavailable) {
            // Price evidence and optional diagnostics cannot affect execution or authoritative Run usage.
        }
    }

    public static Map<String, Long> summarySources(AgentChatRequest request) {
        return Map.of(
                "fixedInstructions",
                visibleTokens(request.messages().getFirst()),
                "summarySource",
                visibleTokens(request.messages().getLast()));
    }
}
