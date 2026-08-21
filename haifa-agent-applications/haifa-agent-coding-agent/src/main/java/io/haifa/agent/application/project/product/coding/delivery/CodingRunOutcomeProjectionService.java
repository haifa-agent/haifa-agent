package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.completion.CompletionPolicyResult;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Separates delivery evidence from final-answer/Run protocol diagnostics. */
public final class CodingRunOutcomeProjectionService {
    private final CodingCompletionPolicy completion;
    private final RuntimeEventAppender events;

    public CodingRunOutcomeProjectionService(CodingCompletionPolicy completion, RuntimeEventAppender events) {
        this.completion = Objects.requireNonNull(completion, "completion must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    public CodingRunOutcomeProjection project(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        CompletionPolicyResult evidence = completion.evaluateEvidence(run);
        CodingCodeResult codeResult =
                evidence.allowed() ? CodingCodeResult.EVIDENCE_SATISFIED : CodingCodeResult.NOT_READY;
        CodingRunProtocolStatus protocol = protocol(run.status(), evidence.allowed());
        LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        run.error().ifPresent(error -> {
            diagnostics.add(error.code().name());
            addCodes(diagnostics, error.details().get("blockerCodes"));
        });
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
                run.id(),
                codeResult,
                protocol,
                evidence.evidenceCodes(),
                List.copyOf(diagnostics),
                !evidence.allowed());
    }

    private static CodingRunProtocolStatus protocol(AgentRunStatus status, boolean evidenceSatisfied) {
        if (status == AgentRunStatus.COMPLETED && evidenceSatisfied) return CodingRunProtocolStatus.CLEAN;
        if (status.isTerminal()) return CodingRunProtocolStatus.UNCLEAN;
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
