package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.completion.CompletionPolicyResult;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Separates delivery evidence from final-answer/Run protocol diagnostics. */
public final class CodingRunOutcomeProjectionService {
    private final CodingCompletionPolicy completion;
    private final RuntimeEventAppender events;
    private final Optional<RunStateRepository> runs;

    public CodingRunOutcomeProjectionService(CodingCompletionPolicy completion, RuntimeEventAppender events) {
        this(completion, events, null);
    }

    public CodingRunOutcomeProjectionService(
            CodingCompletionPolicy completion, RuntimeEventAppender events, RunStateRepository runs) {
        this.completion = Objects.requireNonNull(completion, "completion must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.runs = Optional.ofNullable(runs);
    }

    public Optional<CodingRunOutcomeProjection> find(io.haifa.agent.core.run.AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return runs.flatMap(repository -> repository.find(runId)).map(this::project);
    }

    public CodingRunOutcomeProjection project(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        CompletionPolicyResult evidence = completion.evaluateEvidence(run);
        CodingDeliveryEvidenceStatus deliveryEvidenceStatus =
                evidence.allowed() ? CodingDeliveryEvidenceStatus.SATISFIED : CodingDeliveryEvidenceStatus.INCOMPLETE;
        CodingRunProtocolStatus protocol = protocol(run, evidence.allowed());
        LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        run.error().ifPresent(error -> {
            diagnostics.add(error.code().name());
            addCodes(diagnostics, error.details().get("blockerCodes"));
        });
        run.result().ifPresent(result -> result.warnings().stream()
                .filter(CodingRunOutcomeProjectionService::safeCode)
                .forEach(diagnostics::add));
        events.eventsFor(run.id()).stream()
                .filter(event -> event.type().equals("run.structured-termination"))
                .forEach(event -> {
                    Object reason = event.data().get("reason");
                    if (reason instanceof String code && safeCode(code)) diagnostics.add(code);
                    addCodes(diagnostics, event.data().get("blockerCodes"));
                });
        if (!evidence.allowed()) {
            evidence.blockers().forEach(blocker -> diagnostics.add(blocker.code()));
        }
        return new CodingRunOutcomeProjection(
                run.id(), deliveryEvidenceStatus, protocol, evidence.evidenceCodes(), List.copyOf(diagnostics));
    }

    private static CodingRunProtocolStatus protocol(AgentRun run, boolean evidenceSatisfied) {
        if (run.result()
                .map(result -> result.outcome() == AgentRunOutcome.PARTIAL_SUCCESS)
                .orElse(false)) {
            return CodingRunProtocolStatus.PARTIAL;
        }
        if (run.status() == AgentRunStatus.COMPLETED && evidenceSatisfied) return CodingRunProtocolStatus.CLEAN;
        if (run.status().isTerminal()) return CodingRunProtocolStatus.UNCLEAN;
        return CodingRunProtocolStatus.IN_PROGRESS;
    }

    private static void addCodes(LinkedHashSet<String> target, Object value) {
        if (!(value instanceof List<?> values)) return;
        values.forEach(item -> {
            if (item instanceof String code && safeCode(code)) target.add(code);
        });
    }

    private static boolean safeCode(String value) {
        return value.matches("[A-Z0-9_.:-]{1,96}");
    }
}
