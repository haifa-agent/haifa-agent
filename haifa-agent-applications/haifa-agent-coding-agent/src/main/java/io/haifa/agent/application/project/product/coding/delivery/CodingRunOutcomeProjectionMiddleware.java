package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddleware;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareOrder;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Publishes the separate code-result/protocol projection as a safe persisted client event. */
public final class CodingRunOutcomeProjectionMiddleware implements AgentRuntimeMiddleware {
    private final CodingRunOutcomeProjectionService outcomes;
    private final RuntimeEventAppender events;
    private final TimeProvider time;

    public CodingRunOutcomeProjectionMiddleware(
            CodingRunOutcomeProjectionService outcomes, RuntimeEventAppender events, TimeProvider time) {
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public RuntimePhase phase() {
        return RuntimePhase.AFTER_DECISION_EXECUTION;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(350);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        if (!context.run().status().isTerminal()) return;
        CodingRunOutcomeProjection outcome = outcomes.project(context.run());
        String digest = PolicyDigest.sha256Fields(List.of(
                "coding-run-outcome/1",
                outcome.runId().value(),
                outcome.codeResult().name(),
                outcome.protocolStatus().name(),
                String.join(",", outcome.evidenceCodes()),
                String.join(",", outcome.diagnosticCodes()),
                Boolean.toString(outcome.requiresCodeReexecution())));
        boolean exists = events.eventsFor(context.run().id()).stream()
                .filter(event -> event.type().equals("coding.task-outcome"))
                .anyMatch(event -> digest.equals(event.data().get("projectionDigest")));
        if (exists) return;
        events.append(
                context.run().id(),
                "coding.task-outcome",
                Map.ofEntries(
                        Map.entry("schemaVersion", "coding-run-outcome/1"),
                        Map.entry("codeResult", outcome.codeResult().name()),
                        Map.entry("protocolStatus", outcome.protocolStatus().name()),
                        Map.entry("evidenceCodes", outcome.evidenceCodes()),
                        Map.entry("diagnosticCodes", outcome.diagnosticCodes()),
                        Map.entry("requiresCodeReexecution", outcome.requiresCodeReexecution()),
                        Map.entry("projectionDigest", digest)),
                time.now());
    }
}
