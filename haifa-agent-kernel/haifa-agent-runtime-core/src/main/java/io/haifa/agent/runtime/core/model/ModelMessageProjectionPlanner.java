package io.haifa.agent.runtime.core.model;

import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Plans wire-level tool payload pruning and argument truncation for model message assembly.
 * Enables Tier 1 model-free pruning to bypass Tier 2 LLM compaction while preserving
 * authoritative SQLite facts.
 */
public final class ModelMessageProjectionPlanner {

    public static final String PRUNED_PAYLOAD_NOTICE =
            "\n[Historical large pure-read payload pruned; rerun to inspect current state.]";

    public static final int PROTECTED_TAIL_GROUPS = 2;
    public static final int BATCH_STEP = 2;
    public static final int ARG_TRUNCATION_CHAR_LIMIT = 1024;

    public static final Set<String> DEFAULT_PURE_READ_TOOLS = Set.of(
            "file_read",
            "workspace_file_read",
            "directory_list",
            "project_search",
            "grep_search",
            "find_by_name",
            "read_url_content");

    private static final Set<String> OVERSIZED_ARGUMENT_KEYS = Set.of(
            "codeContent", "replacementContent", "content", "newContent", "patch", "file_content", "text", "data");

    private static final List<String> TARGET_PATH_KEYS =
            List.of("TargetFile", "targetFile", "path", "filePath", "target_file", "file", "fileName", "destination");

    private final RuntimeStateRepository state;

    public ModelMessageProjectionPlanner(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    /**
     * Plans projection from AgentContext and run configuration.
     */
    public ModelMessageProjectionPlan plan(AgentRunId runId, AgentContext context, ResolvedModelSnapshot model) {
        List<AgentMessage> messages = new ArrayList<>();
        for (ContextItem item : context.items()) {
            if (item.content() instanceof MessageGroupContextContent group) {
                messages.addAll(group.messages());
            }
        }
        if (messages.isEmpty()) {
            return ModelMessageProjectionPlan.EMPTY;
        }

        Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        Function<ToolCallId, ToolCall> resolver = callId -> {
            for (AgentMessage message : messages) {
                AgentRunId messageRunId = message.runId().orElse(runId);
                Map<ToolCallId, ToolCall> runCalls =
                        toolCallsByRun.computeIfAbsent(messageRunId, rId -> state.toolCalls(rId).stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        ToolCall::id, Function.identity(), (a, b) -> a)));
                ToolCall call = runCalls.get(callId);
                if (call != null) {
                    return call;
                }
            }
            return null;
        };

        long softLimit = context.budget().availableInputTokens();
        return plan(messages, resolver, DEFAULT_PURE_READ_TOOLS, softLimit);
    }

    /**
     * Core planning method operating on messages and tool calls.
     */
    public ModelMessageProjectionPlan plan(
            List<AgentMessage> messages,
            Function<ToolCallId, ToolCall> toolCallResolver,
            Set<String> pureReadToolNames,
            long softTokenLimit) {
        if (messages.isEmpty()) {
            return ModelMessageProjectionPlan.EMPTY;
        }

        Set<String> activePureReads =
                pureReadToolNames == null || pureReadToolNames.isEmpty() ? DEFAULT_PURE_READ_TOOLS : pureReadToolNames;

        List<AtomicToolGroup> toolGroups = identifyCompletedToolGroups(messages);
        int completedCount = toolGroups.size();
        int candidateCount = Math.max(0, completedCount - PROTECTED_TAIL_GROUPS);
        int eligibleGroupCount = (candidateCount / BATCH_STEP) * BATCH_STEP;

        Set<ToolCallId> prunedToolResults = new HashSet<>();
        Map<ToolCallId, Map<String, Object>> truncatedToolCalls = new LinkedHashMap<>();
        long tokensSaved = 0L;

        for (int i = 0; i < eligibleGroupCount; i++) {
            AtomicToolGroup group = toolGroups.get(i);
            for (ToolCallId callId : group.toolCallIds()) {
                ToolCall call = toolCallResolver.apply(callId);
                if (call == null) {
                    continue;
                }

                // 1. Tool result pruning for pure reads
                if (call.result().isPresent()) {
                    ToolResult result = call.result().get();
                    if (isEligiblePureRead(call, result, activePureReads)) {
                        prunedToolResults.add(callId);
                        int rawDataTokens = HeuristicTokenEstimator.tokens(result.structuredData());
                        int noticeTokens = HeuristicTokenEstimator.tokens(PRUNED_PAYLOAD_NOTICE);
                        tokensSaved += Math.max(0, rawDataTokens - noticeTokens);
                    }
                }

                // 2. Tool argument truncation for oversized code/text arguments
                Map<String, Object> truncatedArgs = truncateArgumentsIfNeeded(call);
                if (truncatedArgs != null) {
                    truncatedToolCalls.put(callId, truncatedArgs);
                    int rawArgTokens =
                            HeuristicTokenEstimator.tokens(call.arguments().values());
                    int truncatedArgTokens = HeuristicTokenEstimator.tokens(truncatedArgs);
                    tokensSaved += Math.max(0, rawArgTokens - truncatedArgTokens);
                }
            }
        }

        long rawActiveTokens = estimateRawActiveTokens(messages, toolCallResolver);
        long projectedActiveTokens = Math.max(0L, rawActiveTokens - tokensSaved);
        boolean bypassRecommended = softTokenLimit > 0 && tokensSaved > 0 && projectedActiveTokens <= softTokenLimit;

        return new ModelMessageProjectionPlan(
                prunedToolResults,
                truncatedToolCalls,
                rawActiveTokens,
                projectedActiveTokens,
                tokensSaved,
                bypassRecommended);
    }

    private boolean isEligiblePureRead(ToolCall call, ToolResult result, Set<String> pureReadToolNames) {
        if (!result.successful()) {
            return false;
        }
        if (result.structuredData().isEmpty()) {
            return false;
        }
        return pureReadToolNames.contains(call.toolName());
    }

    private Map<String, Object> truncateArgumentsIfNeeded(ToolCall call) {
        Map<String, Object> originalArgs = call.arguments().values();
        if (originalArgs == null || originalArgs.isEmpty()) {
            return null;
        }
        boolean hasOversized = false;
        for (Map.Entry<String, Object> entry : originalArgs.entrySet()) {
            if (entry.getValue() instanceof String text
                    && text.length() > ARG_TRUNCATION_CHAR_LIMIT
                    && isOversizedArgumentKey(entry.getKey())) {
                hasOversized = true;
                break;
            }
        }
        if (!hasOversized) {
            return null;
        }

        String targetPath = findTargetPath(originalArgs);
        Map<String, Object> modified = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : originalArgs.entrySet()) {
            if (entry.getValue() instanceof String text
                    && text.length() > ARG_TRUNCATION_CHAR_LIMIT
                    && isOversizedArgumentKey(entry.getKey())) {
                boolean successful = call.result().map(ToolResult::successful).orElse(false);
                String placeholder;
                if (targetPath != null) {
                    placeholder = successful
                            ? "[Code content truncated (" + text.length() + " chars); file written to " + targetPath
                                    + "]"
                            : "[Code content truncated (" + text.length() + " chars); target: " + targetPath + "]";
                } else {
                    placeholder = "[Content truncated (" + text.length() + " chars)]";
                }
                modified.put(entry.getKey(), placeholder);
            } else {
                modified.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(modified);
    }

    private boolean isOversizedArgumentKey(String key) {
        for (String candidate : OVERSIZED_ARGUMENT_KEYS) {
            if (key.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String findTargetPath(Map<String, Object> args) {
        for (String key : TARGET_PATH_KEYS) {
            Object val = args.get(key);
            if (val instanceof String path && !path.isBlank()) {
                return path;
            }
        }
        return null;
    }

    private List<AtomicToolGroup> identifyCompletedToolGroups(List<AgentMessage> messages) {
        List<AtomicToolGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            AgentMessage message = messages.get(index);
            List<ToolCallId> callIds = new ArrayList<>();
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart callPart) {
                    callIds.add(callPart.toolCallId());
                }
            }

            if (!callIds.isEmpty()) {
                Set<ToolCallId> pendingCalls = new HashSet<>(callIds);
                int end = index;
                for (int candidate = index + 1; candidate < messages.size(); candidate++) {
                    for (var content : messages.get(candidate).contents()) {
                        if (content instanceof ToolResultPart resPart && pendingCalls.remove(resPart.toolCallId())) {
                            end = candidate;
                        }
                    }
                    if (pendingCalls.isEmpty()) {
                        break;
                    }
                }
                if (pendingCalls.isEmpty()) {
                    groups.add(new AtomicToolGroup(List.copyOf(callIds)));
                    index = end + 1;
                    continue;
                }
            }
            index++;
        }
        return groups;
    }

    private long estimateRawActiveTokens(List<AgentMessage> messages, Function<ToolCallId, ToolCall> resolver) {
        long total = 0L;
        for (AgentMessage message : messages) {
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart callPart) {
                    ToolCall call = resolver.apply(callPart.toolCallId());
                    if (call != null) {
                        total += HeuristicTokenEstimator.tokens(call.toolName());
                        total += HeuristicTokenEstimator.tokens(call.arguments().values());
                    }
                } else if (content instanceof ToolResultPart resPart) {
                    ToolCall call = resolver.apply(resPart.toolCallId());
                    if (call != null && call.result().isPresent()) {
                        ToolResult res = call.result().get();
                        total += HeuristicTokenEstimator.tokens(res.summary());
                        total += HeuristicTokenEstimator.tokens(res.structuredData());
                    } else {
                        total += HeuristicTokenEstimator.tokens(resPart.summary());
                    }
                } else if (content instanceof io.haifa.agent.core.content.TextPart textPart) {
                    total += HeuristicTokenEstimator.tokens(textPart.text());
                }
            }
        }
        return total;
    }

    private record AtomicToolGroup(List<ToolCallId> toolCallIds) {}
}
