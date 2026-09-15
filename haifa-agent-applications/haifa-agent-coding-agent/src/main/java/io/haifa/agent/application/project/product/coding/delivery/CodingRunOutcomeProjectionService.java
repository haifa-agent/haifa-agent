package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only projection of authoritative Run protocol facts for Coding clients. */
public final class CodingRunOutcomeProjectionService {
    private static final String STRUCTURED_TERMINATION = "run.structured-termination";

    private final RuntimeEventAppender events;
    private final RunStateRepository runs;

    public CodingRunOutcomeProjectionService(RuntimeEventAppender events, RunStateRepository runs) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
    }

    public Optional<CodingRunOutcomeProjection> find(io.haifa.agent.core.run.AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return runs.find(runId).map(this::project);
    }

    public CodingRunOutcomeProjection project(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        run.error().ifPresent(error -> {
            diagnostics.add(error.code().name());
            addCodes(diagnostics, error.details().get("blockerCodes"));
        });
        run.result().ifPresent(result -> result.warnings().stream()
                .filter(CodingRunOutcomeProjectionService::safeCode)
                .forEach(diagnostics::add));
        events.eventsFor(run.id()).stream()
                .filter(event -> event.type().equals(STRUCTURED_TERMINATION))
                .forEach(event -> {
                    Object reason = event.data().get("reason");
                    if (reason instanceof String code && safeCode(code)) diagnostics.add(code);
                    addCodes(diagnostics, event.data().get("blockerCodes"));
                });
        return new CodingRunOutcomeProjection(run.id(), protocol(run), List.copyOf(diagnostics));
    }

    private static CodingRunProtocolStatus protocol(AgentRun run) {
        if (run.result()
                .map(result -> result.outcome() == AgentRunOutcome.PARTIAL_SUCCESS)
                .orElse(false)) {
            return CodingRunProtocolStatus.PARTIAL;
        }
        if (run.status() == AgentRunStatus.COMPLETED) return CodingRunProtocolStatus.CLEAN;
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
