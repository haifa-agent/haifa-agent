package io.haifa.agent.context.core;

import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.context.api.AgentContextBuilder;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.api.ContextBuildResult;
import io.haifa.agent.context.budget.ContextWindowBudget;
import io.haifa.agent.context.budget.TokenEstimator;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.selection.ContextSelectionPolicy;
import io.haifa.agent.context.source.ContextSource;
import io.haifa.agent.context.trace.ContextSelectionDecision;
import io.haifa.agent.context.trace.ContextTrace;
import io.haifa.agent.context.trace.ContextTraceItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic, bounded first-stage builder. Persistent compression is added in phase two. */
public final class DefaultAgentContextBuilder implements AgentContextBuilder {
    private final TokenEstimator estimator;
    private final ContextSelectionPolicy selectionPolicy;
    private final List<ContextSource> sources;

    public DefaultAgentContextBuilder(
            TokenEstimator estimator, ContextSelectionPolicy selectionPolicy, List<ContextSource> sources) {
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
        this.selectionPolicy = Objects.requireNonNull(selectionPolicy, "selectionPolicy must not be null");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    }

    @Override
    public ContextBuildResult build(ContextBuildRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ContextWindowBudget budget = ContextWindowBudget.calculate(
                request.model(),
                request.runBudget(),
                request.runUsage(),
                request.requestedOutputTokens(),
                request.safetyMarginTokens());
        long promptTokens =
                request.prompts().stream().mapToLong(estimator::estimate).sum();
        long toolTokens =
                request.tools().stream().mapToLong(estimator::estimate).sum();
        long fixedTokens = Math.addExact(promptTokens, toolTokens);
        if (fixedTokens > budget.availableInputTokens()) {
            throw new ContextBuildException(
                    ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE,
                    "required prompts and tool definitions exceed the model input budget");
        }

        List<ContextItem> candidates = new ArrayList<>(request.items());
        for (ContextSource source : sources) {
            List<ContextItem> loaded = List.copyOf(source.load(request));
            candidates.addAll(loaded);
        }
        List<IndexedItem> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ranked.add(new IndexedItem(index, candidates.get(index)));
        }
        Comparator<IndexedItem> ranking = Comparator.comparing(IndexedItem::item, selectionPolicy.comparator())
                .thenComparingInt(IndexedItem::index);
        ranked.sort(ranking);

        long remaining = budget.availableInputTokens() - fixedTokens;
        Set<String> hashes = new HashSet<>();
        List<IndexedItem> selected = new ArrayList<>();
        List<ContextTraceItem> traceItems = new ArrayList<>();
        for (IndexedItem candidate : ranked) {
            ContextItem item = candidate.item();
            if (!item.security().providerDisclosureAllowed()) {
                traceItems.add(trace(item, ContextSelectionDecision.DROPPED_SECURITY));
                continue;
            }
            String deduplicationKey = item.type() + ":" + item.provenance().contentHash();
            if (!hashes.add(deduplicationKey)) {
                traceItems.add(trace(item, ContextSelectionDecision.DROPPED_DUPLICATE));
                continue;
            }
            long tokens = estimator.estimate(item);
            if (tokens <= remaining) {
                selected.add(candidate);
                remaining -= tokens;
                traceItems.add(trace(item, ContextSelectionDecision.SELECTED));
            } else if (item.retention() == ContextRetention.MUST_KEEP) {
                throw new ContextBuildException(
                        ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE,
                        "required context item does not fit: " + item.id().value());
            } else {
                traceItems.add(trace(item, ContextSelectionDecision.DROPPED_BUDGET));
            }
        }
        selected.sort(Comparator.comparingInt(IndexedItem::index));
        List<ContextItem> selectedItems =
                selected.stream().map(IndexedItem::item).toList();
        long itemTokens = selectedItems.stream().mapToLong(estimator::estimate).sum();
        long totalTokens = Math.addExact(fixedTokens, itemTokens);
        AgentContext context = new AgentContext(
                request.prompts().stream()
                        .sorted(Comparator.comparing(prompt -> prompt.layer().ordinal()))
                        .toList(),
                selectedItems,
                request.tools(),
                budget,
                totalTokens);
        ContextTrace trace = new ContextTrace(
                request.runId(),
                request.sessionId(),
                request.iteration(),
                request.model().configurationDigest(),
                estimator.version(),
                selectionPolicy.version(),
                request.compressionPolicyVersion(),
                request.compressorVersion(),
                request.forcedRebuildAttempt(),
                promptTokens,
                toolTokens,
                itemTokens,
                traceItems);
        return new ContextBuildResult(context, trace);
    }

    private ContextTraceItem trace(ContextItem item, ContextSelectionDecision decision) {
        return new ContextTraceItem(
                item.id(),
                item.type(),
                item.provenance().sourceType(),
                item.provenance().sourceId(),
                item.provenance().sourceVersion(),
                estimator.estimate(item),
                decision,
                item.provenance().contentHash(),
                item.security().labels());
    }

    private record IndexedItem(int index, ContextItem item) {}
}
