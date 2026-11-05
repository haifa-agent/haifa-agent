package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.api.ContextBuildResult;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import io.haifa.agent.context.trace.ContextReport;
import io.haifa.agent.context.trace.ContextReportComponent;
import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Builds trusted Context IR from frozen run state and persisted facts. */
public final class DefaultRuntimeContextBuilder implements RuntimeContextBuilder {
    private static final int MAX_SKILL_DESCRIPTION_CHARS = 240;

    private final RuntimeStateRepository state;
    private final AgentRuntimeMiddlewareChain middleware;
    private final SessionMessageSource sessionMessages;
    private final MemoryContextSource memorySource;
    private final SkillContentLoader skillContentLoader;
    private final io.haifa.agent.runtime.core.model.FrozenModelInvoker modelInputs;

    public DefaultRuntimeContextBuilder(
            RuntimeStateRepository state,
            AgentRuntimeMiddlewareChain middleware,
            SessionMessageSource sessionMessages,
            MemoryContextSource memorySource) {
        this(state, middleware, sessionMessages, memorySource, SkillContentLoader.empty());
    }

    public DefaultRuntimeContextBuilder(
            RuntimeStateRepository state,
            AgentRuntimeMiddlewareChain middleware,
            SessionMessageSource sessionMessages,
            MemoryContextSource memorySource,
            SkillContentLoader skillContentLoader) {
        this(state, middleware, sessionMessages, memorySource, skillContentLoader, null);
    }

    public DefaultRuntimeContextBuilder(
            RuntimeStateRepository state,
            AgentRuntimeMiddlewareChain middleware,
            SessionMessageSource sessionMessages,
            MemoryContextSource memorySource,
            SkillContentLoader skillContentLoader,
            io.haifa.agent.runtime.core.model.FrozenModelInvoker modelInputs) {
        this.modelInputs = modelInputs;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.middleware = Objects.requireNonNull(middleware, "middleware must not be null");
        this.sessionMessages = Objects.requireNonNull(sessionMessages, "sessionMessages must not be null");
        this.memorySource = Objects.requireNonNull(memorySource, "memorySource must not be null");
        this.skillContentLoader = Objects.requireNonNull(skillContentLoader, "skillContentLoader must not be null");
    }

    @Override
    public RuntimeContextBuildResult build(AgentRun run, AgentLoopContext loopContext, FrozenModelBinding model) {
        long contextBuildStartedAt = System.nanoTime();
        SessionMessageSource.SelectionMetrics selectionMetrics = SessionMessageSource.SelectionMetrics.NONE;
        RuntimeMiddlewareContext middlewareContext = new RuntimeMiddlewareContext(run, state);
        middleware.apply(RuntimePhase.BEFORE_CONTEXT_BUILD, middlewareContext);
        middleware.apply(RuntimePhase.AFTER_CONTEXT_BUILD, middlewareContext);
        List<SkillActivation> activations = addSkillPrompts(run, middlewareContext);
        List<ModelToolSpecification> effectiveTools = skillTools(model, activations);
        if (sessionMessages.workingSetEnabled(run))
            middlewareContext.addPrompt(new PromptComponent(
                    new PromptComponentId("working-set-data-rule"),
                    RunWorkingSetSource.VERSION,
                    PromptLayer.TOOL_PROTOCOL,
                    PromptRole.RUNTIME,
                    RunWorkingSetSource.DATA_RULE,
                    true,
                    java.util.Set.of("working-set", "data-boundary")));
        if (RuntimeControlOptions.finalizeOnly(
                model.configuration().modelRequestOptions(), run.usage().toolCalls())) {
            effectiveTools = List.of();
            middlewareContext.addPrompt(new PromptComponent(
                    new PromptComponentId("runtime-finalize-only"),
                    "1.0",
                    PromptLayer.TOOL_PROTOCOL,
                    PromptRole.RUNTIME,
                    "FINALIZE_ONLY: the governed Tool collection threshold has been reached. No more Tools are available. "
                            + "Finish now using only the evidence already present in the conversation. Return the requested final output; "
                            + "do not ask for or invent additional Tool results. Do not print or serialize Tool-call syntax such as "
                            + "DSML, XML invoke tags, function_call, or tool_calls; such text is not a final answer.",
                    false,
                    java.util.Set.of("runtime-control", "finalize-only")));
        }

        int safetyMargin = io.haifa.agent.context.budget.ContextWindowBudget.safetyMargin(
                model.configuration().model().contextWindow(), loopContext.forcedContextRebuildAttempts() > 0);
        TokenBudget budget = TokenBudget.forModel(
                model.configuration().model(), model.configuration().model().maxOutputTokens(), safetyMargin);
        boolean unified = sessionMessages.workingSetEnabled(run)
                && RuntimeControlOptions.unifiedContextBudget(
                        model.configuration().modelRequestOptions());
        long fixedTokens =
                middlewareContext.prompts().stream().mapToLong(budget::estimate).sum()
                        + effectiveTools.stream().mapToLong(budget::estimate).sum();
        if (unified)
            fixedTokens += model.configuration()
                    .structuredOutput()
                    .map(schema ->
                            (long) io.haifa.agent.context.budget.HeuristicTokenEstimator.tokens(schema.jsonSchema()))
                    .orElse(0L);
        long requiredTokens = fixedTokens
                + middlewareContext.contextItems().stream()
                        .filter(item ->
                                !unified || item.retention() == io.haifa.agent.context.item.ContextRetention.MUST_KEEP)
                        .mapToLong(budget::estimate)
                        .sum();
        requireFits(
                requiredTokens,
                budget,
                "required prompts, tool definitions, and runtime context exceed the model input budget");

        List<ContextItem> memoryItems = loopContext.forcedContextRebuildAttempts() > 0
                ? List.of()
                : memoryThatFits(
                        memorySource.select(run, model, loopContext), budget.availableInputTokens() - requiredTokens);
        long memoryTokens = memoryItems.stream().mapToLong(budget::estimate).sum();
        long sessionTokenBudget = positiveBudget(budget.availableInputTokens() - requiredTokens - memoryTokens);
        SessionMessageSource.MeasuredSelection measuredSelection = sessionMessages.selectMeasured(
                run,
                sessionTokenBudget,
                io.haifa.agent.runtime.core.model.ModelInputAccounting.expectedPrefix(
                        model, middlewareContext.prompts(), effectiveTools));
        selectionMetrics = selectionMetrics.plus(measuredSelection.metrics());
        SessionMessageSource.Selection selection = measuredSelection.selection();
        if (unified)
            return buildUnified(
                    run,
                    loopContext,
                    model,
                    middlewareContext,
                    effectiveTools,
                    budget,
                    memoryItems,
                    selection,
                    selectionMetrics,
                    contextBuildStartedAt);
        ContextAssembly assembly =
                assemble(selection, middlewareContext.contextItems(), memoryItems, budget, fixedTokens);

        // Memory is optional. Remove it before compacting the Session so a transient Memory match
        // cannot cause history to be summarized or a Run to fail unnecessarily.
        if (assembly.exceeds(budget) && !memoryItems.isEmpty()) {
            memoryItems = List.of();
            sessionTokenBudget = positiveBudget(budget.availableInputTokens() - requiredTokens);
            measuredSelection = sessionMessages.selectMeasured(
                    run,
                    sessionTokenBudget,
                    io.haifa.agent.runtime.core.model.ModelInputAccounting.expectedPrefix(
                            model, middlewareContext.prompts(), effectiveTools));
            selectionMetrics = selectionMetrics.plus(measuredSelection.metrics());
            selection = measuredSelection.selection();
            assembly = assemble(selection, middlewareContext.contextItems(), memoryItems, budget, fixedTokens);
        }
        if (assembly.exceeds(budget) || loopContext.forcedContextRebuildAttempts() > 0) {
            measuredSelection = sessionMessages.compactIfNeededMeasured(
                    run, loopContext.forcedContextRebuildAttempts(), sessionTokenBudget);
            selectionMetrics = selectionMetrics.plus(measuredSelection.metrics());
            selection = measuredSelection.selection();
            assembly = assemble(selection, middlewareContext.contextItems(), memoryItems, budget, fixedTokens);
        }
        if (assembly.exceeds(budget)) {
            throw new LocalContextOverflowException();
        }
        List<ContextItem> items = assembly.items();
        long totalTokens = assembly.totalTokens();
        var context =
                new AgentContext(middlewareContext.prompts(), items, effectiveTools, budget.window(), totalTokens);
        var built = new ContextBuildResult(
                context,
                contextReport(run, loopContext, model, middlewareContext, items, selection, budget, totalTokens));
        String windowIdentity =
                windowIdentity(built.report(), middlewareContext, memoryItems, selection, model, effectiveTools);
        long contextBuildElapsedMillis =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - contextBuildStartedAt);
        middlewareContext.put(
                ContextPreparationMetrics.MIDDLEWARE_ATTRIBUTE,
                ContextPreparationMetrics.from(Math.max(0L, contextBuildElapsedMillis), selectionMetrics));
        return new RuntimeContextBuildResult(built, middlewareContext, selection, windowIdentity);
    }

    private RuntimeContextBuildResult buildUnified(
            AgentRun run,
            AgentLoopContext loopContext,
            FrozenModelBinding model,
            RuntimeMiddlewareContext middlewareContext,
            List<ModelToolSpecification> tools,
            TokenBudget budget,
            List<ContextItem> memoryItems,
            SessionMessageSource.Selection selection,
            SessionMessageSource.SelectionMetrics metrics,
            long startedAt) {
        if (modelInputs == null) throw new IllegalStateException("unified context requires model input preparation");
        var items = new ArrayList<ContextItem>(selection.items());
        items.addAll(middlewareContext.contextItems());
        items.addAll(memoryItems);
        var prepared = prepareUnified(run, model, middlewareContext.prompts(), tools, budget, items);
        if (prepared.estimatedTokens() > budget.availableInputTokens() && !memoryItems.isEmpty()) {
            items.removeAll(memoryItems);
            memoryItems = List.of();
            prepared = prepareUnified(run, model, middlewareContext.prompts(), tools, budget, items);
        }
        if (prepared.estimatedTokens() > budget.availableInputTokens()) {
            items.removeIf(item -> item.retention() != io.haifa.agent.context.item.ContextRetention.MUST_KEEP);
            memoryItems = List.of();
            prepared = prepareUnified(run, model, middlewareContext.prompts(), tools, budget, items);
        }
        if (prepared.estimatedTokens() > budget.availableInputTokens()) throw new LocalContextOverflowException();
        var decision = selectedDecision(prepared, items);
        if (decision.shouldCompact()
                && items.stream()
                        .anyMatch(item -> item.retention() != io.haifa.agent.context.item.ContextRetention.MUST_KEEP)) {
            items.removeIf(item -> item.retention() != io.haifa.agent.context.item.ContextRetention.MUST_KEEP);
            memoryItems = List.of();
            prepared = prepareUnified(run, model, middlewareContext.prompts(), tools, budget, items);
            decision = selectedDecision(prepared, items);
        }
        var context = new AgentContext(
                middlewareContext.prompts(), items, tools, prepared.budget(), prepared.estimatedTokens());
        var built = new ContextBuildResult(
                context,
                contextReport(
                        run,
                        loopContext,
                        model,
                        middlewareContext,
                        items,
                        selection,
                        budget,
                        prepared.estimatedTokens()));
        String identity = windowIdentity(built.report(), middlewareContext, memoryItems, selection, model, tools);
        middlewareContext.put(
                ContextPreparationMetrics.MIDDLEWARE_ATTRIBUTE,
                ContextPreparationMetrics.from(
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), metrics));
        return new RuntimeContextBuildResult(
                built,
                middlewareContext,
                selection,
                identity,
                java.util.Optional.of(prepared),
                java.util.Optional.of(decision));
    }

    private io.haifa.agent.runtime.core.compaction.CompactionTriggerDecision selectedDecision(
            io.haifa.agent.runtime.core.model.PreparedModelInput prepared, List<ContextItem> items) {
        long completeTools = items.stream()
                .filter(item -> item.content() instanceof MessageGroupContextContent)
                .flatMap(item -> ((MessageGroupContextContent) item.content()).messages().stream())
                .flatMap(message -> message.contents().stream())
                .filter(io.haifa.agent.core.content.ToolResultPart.class::isInstance)
                .count();
        return new io.haifa.agent.runtime.core.compaction.CompactionTriggerEvaluator(
                        sessionMessages.compressionPolicy())
                .evaluate(
                        prepared.budget(),
                        prepared.fixedTokens(),
                        prepared.otherTokens(),
                        prepared.sessionTokens(),
                        (int) Math.min(Integer.MAX_VALUE, completeTools + 1));
    }

    private io.haifa.agent.runtime.core.model.PreparedModelInput prepareUnified(
            AgentRun run,
            FrozenModelBinding model,
            List<PromptComponent> prompts,
            List<ModelToolSpecification> tools,
            TokenBudget budget,
            List<ContextItem> items) {
        items.stream()
                .filter(item -> item.content() instanceof MessageGroupContextContent)
                .flatMap(item -> ((MessageGroupContextContent) item.content()).messages().stream())
                .forEach(this::validateContents);
        // Provisional IR is never sent or published; the final context receives the actual prepared estimate.
        return modelInputs.prepare(model, run, new AgentContext(prompts, items, tools, budget.window(), 0));
    }

    private List<ContextItem> memoryThatFits(List<ContextItem> candidates, long remainingInputTokens) {
        long memoryAllowance = Math.max(0L, remainingInputTokens / 8L);
        long used = 0L;
        List<ContextItem> fitting = new ArrayList<>();
        for (ContextItem candidate : candidates) {
            long tokens = candidate.estimatedTokens();
            if (used + tokens > memoryAllowance) {
                break;
            }
            fitting.add(candidate);
            used += tokens;
        }
        return List.copyOf(fitting);
    }

    private ContextAssembly assemble(
            SessionMessageSource.Selection selection,
            List<ContextItem> runtimeItems,
            List<ContextItem> memoryItems,
            TokenBudget budget,
            long requiredTokens) {
        selection.items().stream()
                .filter(item -> item.content() instanceof MessageGroupContextContent)
                .map(item -> (MessageGroupContextContent) item.content())
                .flatMap(group -> group.messages().stream())
                .forEach(this::validateContents);
        // Mutable snapshots follow the append-only Session prefix. Their provenance digests are traced as
        // explicit window-boundary inputs when a Plan or governed Memory selection changes.
        List<ContextItem> items = new ArrayList<>(selection.items());
        items.addAll(runtimeItems);
        items.addAll(memoryItems);
        long itemTokens = items.stream().mapToLong(budget::estimate).sum();
        return new ContextAssembly(List.copyOf(items), Math.addExact(requiredTokens, itemTokens));
    }

    private static long positiveBudget(long value) {
        return Math.max(1L, value);
    }

    private static void requireFits(long tokens, TokenBudget budget, String message) {
        if (tokens > budget.availableInputTokens()) {
            throw new ContextBuildException(ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE, message);
        }
    }

    private record ContextAssembly(List<ContextItem> items, long totalTokens) {
        private boolean exceeds(TokenBudget budget) {
            return totalTokens > budget.availableInputTokens();
        }
    }

    private ContextReport contextReport(
            AgentRun run,
            AgentLoopContext loopContext,
            FrozenModelBinding model,
            RuntimeMiddlewareContext middlewareContext,
            List<ContextItem> items,
            SessionMessageSource.Selection selection,
            TokenBudget budget,
            long totalTokens) {
        List<ContextReportComponent> components = new ArrayList<>();
        middlewareContext
                .prompts()
                .forEach(prompt -> components.add(new ContextReportComponent(
                        prompt.id().value(),
                        ContextReportComponent.ComponentKind.PROMPT,
                        "prompt",
                        "prompt",
                        prompt.layer(),
                        prompt.role(),
                        prompt.version(),
                        budget.estimate(prompt),
                        sha256(prompt.text()),
                        prompt.securityLabels())));
        items.forEach(item -> components.add(new ContextReportComponent(
                item.id().value(),
                ContextReportComponent.ComponentKind.CONTEXT,
                item.provenance().sourceType(),
                item.provenance().sourceId(),
                null,
                null,
                item.provenance().sourceVersion(),
                budget.estimate(item),
                item.provenance().contentHash(),
                item.security().labels())));
        return new ContextReport(
                run.id(),
                run.sessionId(),
                loopContext.iteration(),
                model.configuration().model().configurationDigest(),
                sessionMessages.workingSetEnabled(run)
                                && RuntimeControlOptions.unifiedContextBudget(
                                        model.configuration().modelRequestOptions())
                        ? io.haifa.agent.runtime.core.model.ModelInputAccounting.ADMISSION_VERSION
                        : budget.estimatorVersion(),
                "runtime-fit-only-v1",
                selection.policyVersion(),
                selection.compressorVersion(),
                loopContext.forcedContextRebuildAttempts(),
                totalTokens,
                components);
    }

    private String windowIdentity(
            ContextReport report,
            RuntimeMiddlewareContext middlewareContext,
            List<ContextItem> memoryItems,
            SessionMessageSource.Selection selection,
            FrozenModelBinding model,
            List<ModelToolSpecification> effectiveTools) {
        List<String> components = new ArrayList<>();
        components.add("configuration:" + model.configuration().reference().contentHash());
        report.components().stream()
                .filter(component -> component.kind() == ContextReportComponent.ComponentKind.PROMPT)
                .forEach(component -> components.add(
                        "prompt:" + component.id() + ":" + component.version() + ":" + component.contentHash()));
        effectiveTools.forEach(tool -> components.add("tool:" + tool.name() + ":" + tool.version() + ":"
                + tool.inputSchemaId() + ":" + tool.inputSchemaVersion()));
        middlewareContext
                .contextItems()
                .forEach(item -> components.add("runtime-item:"
                        + item.provenance().sourceType() + ":"
                        + item.provenance().sourceId() + ":"
                        + item.provenance().sourceVersion() + ":"
                        + item.provenance().contentHash()));
        memoryItems.forEach(item -> components.add("memory:"
                + item.provenance().sourceId() + ":"
                + item.provenance().sourceVersion() + ":"
                + item.provenance().contentHash()));
        components.add("compression:" + selection.policyVersion() + ":" + selection.compressorVersion());
        components.add("summary:"
                + selection
                        .summary()
                        .map(summary -> summary.version().value() + ":" + summary.sourceHash())
                        .orElse("none"));
        return sha256(String.join("|", components));
    }

    private static List<ModelToolSpecification> skillTools(
            FrozenModelBinding model, List<SkillActivation> activations) {
        boolean builtInRead = model.configuration().toolBindings().stream()
                .anyMatch(binding -> binding.alias().equals(SkillToolProvider.RESOURCE_READ_ALIAS)
                        && binding.definition().providerId().equals(SkillToolProvider.PROVIDER_ID));
        boolean readable = activations.stream()
                .anyMatch(activation -> activation.binding().packageIndex().resources().stream()
                        .anyMatch(resource -> resource.readableText()
                                && !resource.relativePath().equals("SKILL.md")));
        if (!builtInRead || readable) return model.tools();
        // Frozen bindings and invocation authorization stay intact. Disclose the built-in
        // reader only after this Run has an activated Skill with readable resources.
        return model.tools().stream()
                .filter(tool -> !tool.name().equals(SkillToolProvider.RESOURCE_READ_ALIAS.value()))
                .toList();
    }

    private List<SkillActivation> addSkillPrompts(AgentRun run, RuntimeMiddlewareContext context) {
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        if (!configuration.skillBindings().isEmpty()) {
            String index = configuration.skillBindings().stream()
                    .map(binding -> "- " + binding.alias().value() + ": "
                            + boundedDescription(binding.metadata().description()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            context.addPrompt(new PromptComponent(
                    new PromptComponentId("skill-catalog"),
                    configuration.skillCatalogDigest().value(),
                    PromptLayer.TOOL_PROTOCOL,
                    PromptRole.RUNTIME,
                    "Available Skills are metadata-only until skill_load activates one:\n" + index,
                    true,
                    java.util.Set.of(
                            "skill",
                            "progressive-disclosure",
                            "skill-catalog:"
                                    + configuration.skillCatalogDigest().value(),
                            "skill-policy:" + configuration.skillResolutionPolicyRef())));
        }
        var visibility = new SkillVisibilityContext(
                run.tenant(),
                run.principal(),
                run.project(),
                run.project().isPresent(),
                java.util.EnumSet.allOf(SkillScope.class));
        List<SkillActivation> activations = state.skillActivations(run.id());
        activations.forEach(activation -> {
            var content = skillContentLoader.load(activation.binding(), visibility);
            context.addPrompt(new PromptComponent(
                    new PromptComponentId(
                            "skill-" + activation.binding().alias().value()),
                    activation.binding().coordinate().contentDigest().value(),
                    PromptLayer.SKILL,
                    PromptRole.RUNTIME,
                    content.instructions(),
                    true,
                    java.util.Set.of(
                            "skill",
                            "activated",
                            "skill-alias:" + activation.binding().alias().value(),
                            "skill-scope:"
                                    + activation
                                            .binding()
                                            .coordinate()
                                            .scope()
                                            .scope()
                                            .name()
                                            .toLowerCase(Locale.ROOT),
                            "skill-source:"
                                    + activation.binding().coordinate().source().externalForm(),
                            "skill-content:"
                                    + activation
                                            .binding()
                                            .coordinate()
                                            .contentDigest()
                                            .value(),
                            "skill-activation-reason:" + sha256(activation.reason()))));
        });
        return activations;
    }

    private static String boundedDescription(String value) {
        return value.length() <= MAX_SKILL_DESCRIPTION_CHARS
                ? value
                : value.substring(0, MAX_SKILL_DESCRIPTION_CHARS - 1) + "…";
    }

    private static String sha256(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private void validateContents(AgentMessage message) {
        for (ContentPart part : message.contents()) {
            if (part instanceof AssetRefPart || part instanceof ArtifactRefPart) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "asset and artifact references require an authorized derived text context source");
            }
        }
    }
}
