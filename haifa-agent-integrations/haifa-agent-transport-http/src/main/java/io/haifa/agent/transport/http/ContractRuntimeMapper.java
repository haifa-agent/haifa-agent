package io.haifa.agent.transport.http;

import io.haifa.agent.contract.common.ContentPartDto;
import io.haifa.agent.contract.common.IdempotencyKey;
import io.haifa.agent.contract.common.ReferenceContentPartDto;
import io.haifa.agent.contract.common.TextContentPartDto;
import io.haifa.agent.contract.event.RunEventEnvelope;
import io.haifa.agent.contract.event.RunEventPayload;
import io.haifa.agent.contract.interaction.InteractionResponseRequest;
import io.haifa.agent.contract.run.RunInputRequest;
import io.haifa.agent.contract.run.RuntimeCommandRequest;
import io.haifa.agent.contract.run.StartRunRequest;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Explicit allowlist mapper between wire Contract objects and embedded Runtime API values. */
public final class ContractRuntimeMapper {
    private final RunEventCursorTokenCodec cursors;

    public ContractRuntimeMapper(RunEventCursorTokenCodec cursors) {
        this.cursors = java.util.Objects.requireNonNull(cursors, "cursors must not be null");
    }

    public AgentRunRequest start(StartRunRequest request) {
        return new AgentRunRequest(
                request.idempotencyKey().value(),
                new AgentDefinitionId(request.agentDefinitionId()),
                request.requestedDefinitionVersion().map(ContractRuntimeMapper::definitionVersion),
                request.productProfileId(),
                new AgentSessionId(request.sessionId()),
                request.projectRef().map(ProjectRef::new),
                request.objective(),
                contents(request.inputs()),
                RuntimeOverrides.NONE);
    }

    public RunInputSubmission input(RunInputRequest request) {
        return new RunInputSubmission(
                new RunInputId(request.inputId()),
                new AgentRunId(request.runId()),
                request.expectedRunVersion(),
                contents(request.contents()),
                request.idempotencyKey().value(),
                request.submittedAt());
    }

    public RuntimeCommand command(RuntimeCommandRequest request) {
        RuntimeCommandType type;
        try {
            type = RuntimeCommandType.valueOf(
                    request.commandType().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown commandType", exception);
        }
        return new RuntimeCommand(
                new RuntimeCommandId(request.commandId()),
                new AgentRunId(request.runId()),
                type,
                RuntimeCommandArguments.NONE,
                request.expectedRunVersion(),
                request.idempotencyKey().value(),
                request.requestedAt());
    }

    public InteractionResponseSubmission interaction(InteractionResponseRequest request) {
        return new InteractionResponseSubmission(
                new InteractionResponseId(request.responseId()),
                new InteractionRequestId(request.requestId()),
                new AgentRunId(request.runId()),
                request.expectedRevision(),
                new InteractionAction(request.action()),
                contents(request.inputs()),
                request.idempotencyKey().value(),
                request.respondedAt());
    }

    public io.haifa.agent.contract.run.RunView runView(
            AgentRunViewSnapshot view,
            Optional<String> pendingInteractionId,
            Optional<String> baselineCursor,
            Optional<String> headCursor) {
        var snapshot = view.snapshot();
        return new io.haifa.agent.contract.run.RunView(
                io.haifa.agent.contract.common.ApiVersion.CURRENT,
                snapshot.runId().value(),
                view.sessionId().value(),
                snapshot.status().name(),
                snapshot.version(),
                snapshot.updatedAt(),
                snapshot.output(),
                snapshot.error().map(error -> error.code().value()),
                pendingInteractionId,
                baselineCursor,
                headCursor);
    }

    public io.haifa.agent.contract.interaction.InteractionView interaction(
            io.haifa.agent.runtime.api.InteractionView view) {
        var input = view.inputContract();
        return new io.haifa.agent.contract.interaction.InteractionView(
                io.haifa.agent.contract.common.ApiVersion.CURRENT,
                view.requestId().value(),
                view.runId().value(),
                view.sessionId().value(),
                view.revision(),
                view.kind().value(),
                view.state().name(),
                view.title(),
                view.safePrompt(),
                view.allowedActions().stream().map(InteractionAction::value).toList(),
                new io.haifa.agent.contract.interaction.InteractionView.InputContract(
                        input.type().value(),
                        input.maximumCharacters(),
                        input.minimumSelections(),
                        input.maximumSelections(),
                        input.maximumParts(),
                        input.maximumBytes(),
                        input.options().stream()
                                .map(option -> new io.haifa.agent.contract.interaction.InteractionView.Option(
                                        option.id(), option.label()))
                                .toList(),
                        input.schemaRef()),
                new io.haifa.agent.contract.interaction.InteractionView.TargetView(
                        view.target().type(),
                        view.target().reference(),
                        view.target().version(),
                        view.target().digest(),
                        view.target().safeSummary()),
                new io.haifa.agent.contract.interaction.InteractionView.RequesterView(
                        view.requester().principalType(), view.requester().displayLabel()),
                view.createdAt(),
                view.expiresAt(),
                new io.haifa.agent.contract.interaction.InteractionView.ConsequenceView(
                        view.consequences().accepted(),
                        view.consequences().rejected(),
                        view.consequences().expired()));
    }

    public RunEventEnvelope<? extends RunEventPayload> event(AgentRunEvent event) {
        return new RunEventEnvelope<>(
                io.haifa.agent.contract.common.ApiVersion.CURRENT,
                event.eventId(),
                event.eventType(),
                event.eventSchemaVersion(),
                event.runId().value(),
                event.sessionId().value(),
                event.sequence(),
                new io.haifa.agent.contract.event.RunEventCursor(cursors.encode(event.cursor())),
                event.occurredAt(),
                event.correlationId().map(io.haifa.agent.contract.common.CorrelationId::new),
                event.causationId(),
                payload(event.payload()));
    }

    public io.haifa.agent.contract.event.RunEventPage eventPage(io.haifa.agent.runtime.api.RunEventPage page) {
        return new io.haifa.agent.contract.event.RunEventPage(
                page.items().stream().map(this::event).toList(),
                new io.haifa.agent.contract.event.RunEventCursor(cursors.encode(page.nextCursor())),
                new io.haifa.agent.contract.event.RunEventCursor(cursors.encode(page.headCursor())),
                page.hasMore());
    }

    public RunEventCursorTokenCodec cursors() {
        return cursors;
    }

    private static List<ContentPart> contents(List<? extends ContentPartDto> parts) {
        return parts.stream().map(ContractRuntimeMapper::content).toList();
    }

    private static ContentPart content(ContentPartDto part) {
        if (part instanceof TextContentPartDto text) return new TextPart(text.text(), text.format());
        if (part instanceof ReferenceContentPartDto reference
                && reference.referenceType().equals("asset")) {
            return new AssetRefPart(new AssetRef(
                    reference.reference(),
                    reference.mediaType().orElse("application/octet-stream"),
                    "referenced-asset"));
        }
        throw new IllegalArgumentException("unsupported content reference type");
    }

    private static AgentDefinitionVersion definitionVersion(String version) {
        String[] parts = version.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("definition version must use major.minor.patch");
        try {
            return new AgentDefinitionVersion(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("definition version must contain numeric components", exception);
        }
    }

    private static RunEventPayload payload(AgentRunEvent.Payload payload) {
        if (payload instanceof RunEventPayloads.RunLifecycle value) {
            return new RunEventPayload.RunLifecycle(value.status(), value.version(), value.reasonCode());
        }
        if (payload instanceof RunEventPayloads.AssistantOutput value) {
            return new RunEventPayload.AssistantOutput(
                    value.generationId(), value.status(), value.referenceOrSafeCode());
        }
        if (payload instanceof RunEventPayloads.AssistantTextDelta value) {
            return new RunEventPayload.AssistantTextDelta(value.generationId(), value.textDelta());
        }
        if (payload instanceof RunEventPayloads.InteractionLifecycle value) {
            return new RunEventPayload.InteractionLifecycle(
                    value.requestId(), value.kind(), value.state(), value.actionOrReason());
        }
        if (payload instanceof RunEventPayloads.RunInputLifecycle value) {
            return new RunEventPayload.RunInputLifecycle(value.inputId(), value.state(), value.applicationCoordinate());
        }
        if (payload instanceof RunEventPayloads.CommandResult value) {
            return new RunEventPayload.CommandResult(value.commandId(), value.commandType(), value.status());
        }
        throw new IllegalArgumentException("unsupported public event payload");
    }

    public static IdempotencyKey idempotency(String value) {
        return new IdempotencyKey(value);
    }
}
