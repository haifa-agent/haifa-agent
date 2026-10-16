package io.haifa.agent.runtime.core.model;

import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Plans the wire-level omission of large historical pure-read results for model message assembly.
 *
 * <p>The planner never fabricates or truncates Tool Call arguments: authoritative argument values always reach the
 * provider unchanged. It only replaces an eligible result payload with a fixed omission notice and reports the token
 * estimate of the actual wire representation so the caller can decide whether the projected request still fits.
 *
 * <p>Only complete atomic tool groups are considered, the two most recent groups are always protected, and older
 * groups are advanced in whole-group batches of two. A group is eligible only when every member qualifies; a parallel
 * group that contains an ineligible member, an unresolved call or an incomplete result is preserved entirely.
 */
public final class ModelMessageProjectionPlanner {

    public static final String PRUNED_PAYLOAD_NOTICE =
            "\n[Historical large pure-read payload pruned; rerun the tool to inspect current state, "
                    + "which may differ from the historical bytes.]";

    public static final int PROTECTED_TAIL_GROUPS = 2;
    public static final int BATCH_STEP = 2;

    private final ToolResultPruningQualifier qualifier;

    /**
     * Convenience constructor for callers that also expose Run lookup. Production wiring passes the qualifier
     * explicitly; a store without Run lookup fails closed and disables pruning.
     */
    public ModelMessageProjectionPlanner(RuntimeStateRepository state) {
        this(new FrozenBindingToolResultQualifier(
                state instanceof RunStateRepository runs ? runs : null,
                Objects.requireNonNull(state, "state must not be null")));
    }

    public ModelMessageProjectionPlanner(ToolResultPruningQualifier qualifier) {
        this.qualifier = Objects.requireNonNull(qualifier, "qualifier must not be null");
    }

    /**
     * Plans projection for the supplied messages using the given authoritative Tool Call resolver.
     *
     * <p>An empty qualification set (a qualifier that accepts nothing, or no resolved bindings) disables pruning.
     */
    public ModelMessageProjectionPlan plan(
            List<AgentMessage> messages, Function<ToolCallId, ToolCall> toolCallResolver) {
        if (messages.isEmpty()) {
            return ModelMessageProjectionPlan.EMPTY;
        }

        List<AtomicToolGroup> completedGroups = identifyCompletedToolGroups(messages);
        int candidateCount = Math.max(0, completedGroups.size() - PROTECTED_TAIL_GROUPS);
        List<AtomicToolGroup> eligibleCandidates = new ArrayList<>();
        for (int index = 0; index < candidateCount; index++) {
            AtomicToolGroup group = completedGroups.get(index);
            if (isEligibleGroup(group, toolCallResolver)) {
                eligibleCandidates.add(group);
            }
        }

        int prunedGroupCount = (eligibleCandidates.size() / BATCH_STEP) * BATCH_STEP;
        Set<ToolCallId> prunedToolResults = new HashSet<>();
        for (int index = 0; index < prunedGroupCount; index++) {
            prunedToolResults.addAll(eligibleCandidates.get(index).toolCallIds());
        }

        long rawActiveTokens = estimateProjectedTokens(messages, toolCallResolver, Set.of());
        long projectedActiveTokens = estimateProjectedTokens(messages, toolCallResolver, prunedToolResults);
        long tokensSaved = Math.max(0L, rawActiveTokens - projectedActiveTokens);

        return new ModelMessageProjectionPlan(prunedToolResults, rawActiveTokens, projectedActiveTokens, tokensSaved);
    }

    private boolean isEligibleGroup(AtomicToolGroup group, Function<ToolCallId, ToolCall> toolCallResolver) {
        if (group.toolCallIds().isEmpty()) {
            return false;
        }
        for (ToolCallId callId : group.toolCallIds()) {
            ToolCall call = toolCallResolver.apply(callId);
            if (call == null || call.result().isEmpty() || !qualifier.isPrunable(call)) {
                return false;
            }
        }
        return true;
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

    /**
     * Estimates the exact wire representation the assembler sends: arguments are always authoritative, and a pruned
     * result contributes its summary plus the fixed omission notice instead of its structured payload.
     */
    private long estimateProjectedTokens(
            List<AgentMessage> messages, Function<ToolCallId, ToolCall> resolver, Set<ToolCallId> prunedToolResults) {
        long total = 0L;
        for (AgentMessage message : messages) {
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart callPart) {
                    ToolCall call = resolver.apply(callPart.toolCallId());
                    if (call != null) {
                        total += HeuristicTokenEstimator.tokens(call.toolName());
                        total += HeuristicTokenEstimator.tokens(call.arguments().values());
                    } else {
                        total += HeuristicTokenEstimator.tokens(callPart.toolName());
                        total += HeuristicTokenEstimator.tokens(
                                callPart.providerCorrelationId().value());
                        total += 16L;
                    }
                } else if (content instanceof ToolResultPart resPart) {
                    ToolCall call = resolver.apply(resPart.toolCallId());
                    if (call != null && call.result().isPresent()) {
                        ToolResult result = call.result().get();
                        if (prunedToolResults.contains(call.id())) {
                            total += HeuristicTokenEstimator.tokens(result.summary() + PRUNED_PAYLOAD_NOTICE);
                        } else {
                            total += HeuristicTokenEstimator.tokens(result.summary());
                            total += HeuristicTokenEstimator.tokens(result.structuredData());
                        }
                    } else {
                        total += HeuristicTokenEstimator.tokens(resPart.summary());
                    }
                } else if (content instanceof TextPart textPart) {
                    total += HeuristicTokenEstimator.tokens(textPart.text());
                }
            }
        }
        return total;
    }

    private record AtomicToolGroup(List<ToolCallId> toolCallIds) {}
}
