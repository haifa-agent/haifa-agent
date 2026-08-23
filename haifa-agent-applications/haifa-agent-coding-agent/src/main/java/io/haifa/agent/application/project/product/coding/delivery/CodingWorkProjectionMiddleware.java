package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddleware;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareOrder;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Appends material projection changes as agent-visible control messages and safe client events. */
public final class CodingWorkProjectionMiddleware implements AgentRuntimeMiddleware {
    private final CodingWorkProjectionService projections;
    private final RuntimePhase phase;
    private final RuntimeEventAppender events;
    private final TimeProvider time;

    private CodingWorkProjectionMiddleware(
            CodingWorkProjectionService projections,
            RuntimePhase phase,
            RuntimeEventAppender events,
            TimeProvider time) {
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.events = events;
        this.time = time;
    }

    public static CodingWorkProjectionMiddleware events(
            CodingWorkProjectionService projections,
            RuntimePhase phase,
            RuntimeEventAppender events,
            TimeProvider time) {
        if (phase != RuntimePhase.BEFORE_RUN && phase != RuntimePhase.AFTER_DECISION_EXECUTION) {
            throw new IllegalArgumentException("work projection events support BEFORE_RUN or AFTER_DECISION_EXECUTION");
        }
        return new CodingWorkProjectionMiddleware(
                projections,
                phase,
                Objects.requireNonNull(events, "events must not be null"),
                Objects.requireNonNull(time, "time must not be null"));
    }

    @Override
    public RuntimePhase phase() {
        return phase;
    }

    @Override
    public RuntimeMiddlewareOrder order() {
        return new RuntimeMiddlewareOrder(250);
    }

    @Override
    public void apply(RuntimeMiddlewareContext context) {
        CodingWorkProjection projection = projections.project(context.run());
        boolean unchanged = events.eventsFor(context.run().id()).stream()
                .filter(event -> event.type().equals("coding.work-phase"))
                .reduce((first, second) -> second)
                .map(event -> projection.phase().name().equals(event.data().get("phase"))
                        && projection.missingEvidence().equals(event.data().get("missingEvidence"))
                        && Boolean.valueOf(projection.deliveryReserveActive())
                                .equals(event.data().get("deliveryReserveActive")))
                .orElse(false);
        if (unchanged) return;
        appendAgentProjection(context, projection);
        events.append(
                context.run().id(),
                "coding.work-phase",
                Map.ofEntries(
                        Map.entry("phase", projection.phase().name()),
                        Map.entry("status", projection.phase() == CodingWorkPhase.BLOCKED ? "BLOCKED" : "ACTIVE"),
                        Map.entry("reasonCode", "AUTHORITATIVE_EVIDENCE_PROJECTION"),
                        Map.entry("missingEvidence", projection.missingEvidence()),
                        Map.entry("deliveryReserveActive", projection.deliveryReserveActive()),
                        Map.entry("remainingPercent", projection.remainingPercent()),
                        Map.entry("attempt", 0),
                        Map.entry("projectionDigest", projection.digest()),
                        Map.entry("taskContractDigest", projection.taskContractDigest()),
                        Map.entry("schemaVersion", projection.schemaVersion())),
                time.now());
    }

    private void appendAgentProjection(RuntimeMiddlewareContext context, CodingWorkProjection projection) {
        boolean alreadyAppended = context.state().messages(context.run().id()).stream()
                .filter(message -> Boolean.TRUE.equals(message.metadata().get("codingWorkProjection")))
                .reduce((first, second) -> second)
                .map(message ->
                        projection.phase().name().equals(message.metadata().get("phase"))
                                && projection
                                        .missingEvidence()
                                        .equals(message.metadata().get("missingEvidence"))
                                && Boolean.valueOf(projection.deliveryReserveActive())
                                        .equals(message.metadata().get("deliveryReserveActive")))
                .orElse(false);
        if (alreadyAppended) return;
        context.state()
                .appendSessionMessage(new SessionMessageDraft(
                        new AgentMessageId("coding-work-" + projection.digest()),
                        context.run().sessionId(),
                        Optional.of(context.run().id()),
                        Optional.empty(),
                        MessageRole.RUNTIME,
                        MessageStatus.COMPLETED,
                        MessageVisibility.AGENT_VISIBLE,
                        List.of(new TextPart(projection.contextText(), "plain")),
                        Map.ofEntries(
                                Map.entry("codingWorkProjection", true),
                                Map.entry("schemaVersion", projection.schemaVersion()),
                                Map.entry("phase", projection.phase().name()),
                                Map.entry("missingEvidence", projection.missingEvidence()),
                                Map.entry("deliveryReserveActive", projection.deliveryReserveActive()),
                                Map.entry("projectionDigest", projection.digest())),
                        time.now()));
    }
}
