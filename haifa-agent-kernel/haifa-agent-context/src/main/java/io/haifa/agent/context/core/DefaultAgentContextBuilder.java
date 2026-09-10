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
import io.haifa.agent.context.trace.ContextReport;
import io.haifa.agent.context.trace.ContextReportComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    public DefaultAgentContextBuilder(TokenEstimator estimator, ContextSelectionPolicy selectionPolicy) {
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
        this.selectionPolicy = Objects.requireNonNull(selectionPolicy, "selectionPolicy must not be null");
    }

    @Override
    public ContextBuildResult build(ContextBuildRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ContextWindowBudget budget = ContextWindowBudget.calculate(
                request.model(), request.requestedOutputTokens(), request.safetyMarginTokens());
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
        for (IndexedItem candidate : ranked) {
            ContextItem item = candidate.item();
            String deduplicationKey = item.type() + ":" + item.provenance().contentHash();
            if (!hashes.add(deduplicationKey)) {
                continue;
            }
            long tokens = estimator.estimate(item);
            if (tokens <= remaining) {
                selected.add(candidate);
                remaining -= tokens;
            } else if (item.retention() == ContextRetention.MUST_KEEP) {
                throw new ContextBuildException(
                        ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE,
                        "required context item does not fit: " + item.id().value());
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
        List<ContextReportComponent> components = new ArrayList<>();
        context.prompts().forEach(prompt -> components.add(promptComponent(prompt)));
        selectedItems.forEach(item -> components.add(contextComponent(item)));
        ContextReport report = new ContextReport(
                request.runId(),
                request.sessionId(),
                request.iteration(),
                request.model().configurationDigest(),
                estimator.version(),
                selectionPolicy.version(),
                request.compressionPolicyVersion(),
                request.compressorVersion(),
                request.forcedRebuildAttempt(),
                totalTokens,
                components);
        return new ContextBuildResult(context, report);
    }

    private ContextReportComponent contextComponent(ContextItem item) {
        return new ContextReportComponent(
                item.id().value(),
                ContextReportComponent.ComponentKind.CONTEXT,
                item.provenance().sourceType(),
                item.provenance().sourceId(),
                null,
                null,
                item.provenance().sourceVersion(),
                estimator.estimate(item),
                item.provenance().contentHash(),
                item.security().labels());
    }

    private ContextReportComponent promptComponent(io.haifa.agent.context.prompt.PromptComponent prompt) {
        return new ContextReportComponent(
                prompt.id().value(),
                ContextReportComponent.ComponentKind.PROMPT,
                "prompt",
                "prompt",
                prompt.layer(),
                prompt.role(),
                prompt.version(),
                estimator.estimate(prompt),
                sha256(prompt.text()),
                prompt.securityLabels());
    }

    private static String sha256(String value) {
        try {
            return "sha256:"
                    + java.util.HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record IndexedItem(int index, ContextItem item) {}
}
