package io.haifa.agent.application.coding.terminal.application;

import io.haifa.agent.application.coding.terminal.state.TerminalSessionView;
import io.haifa.agent.application.project.product.coding.*;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.model.api.*;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.coding.api.CodingProtocol.*;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts product wire facts into the shared display values used by the terminal. */
final class TerminalProductFacts {
    private TerminalProductFacts() {}

    static CodingSessionSummary summary(SessionSummary value) {
        return new CodingSessionSummary(
                new AgentSessionId(value.sessionId()),
                new ProjectId(value.projectId()),
                value.title(),
                AgentSessionStatus.valueOf(value.status()),
                Optional.ofNullable(value.runId()).map(AgentRunId::new),
                Optional.ofNullable(value.runStatus()).map(AgentRunStatus::valueOf),
                Math.toIntExact(value.queuedCount()),
                Instant.ofEpochMilli(value.lastActivityAtEpochMillis()),
                value.revision());
    }

    static TerminalSessionView session(TerminalSession value) {
        var selection = value.model();
        return new TerminalSessionView(
                summary(value.summary()),
                Optional.ofNullable(value.activeRun())
                        .map(run -> new TerminalSessionView.Run(
                                new AgentRunId(run.id()), AgentRunStatus.valueOf(run.status()), run.revision())),
                Optional.empty(),
                value.profile(),
                new CodingModelSelection(model(selection.model()), selection.revision(), selection.available()),
                Optional.ofNullable(value.activeTask()));
    }

    static CodingModelOption model(ModelChoice value) {
        var response = value.responseMode();
        var effort = value.reasoningEffort();
        return new CodingModelOption(
                value.id(),
                value.displayName(),
                value.providerId(),
                value.providerDisplayName(),
                Set.copyOf(value.capabilities()),
                Math.toIntExact(value.contextWindow()),
                Math.toIntExact(value.maxOutputTokens()),
                new CodingModelState(
                        CodingModelState.Connection.valueOf(value.connection()),
                        CodingModelState.BindingAvailability.valueOf(value.bindingAvailability()),
                        CodingModelState.RuntimeStatus.valueOf(value.runtimeHealth()),
                        CodingModelState.RunScope.valueOf(value.runScope())),
                value.unavailableReason(),
                new CodingModelControls(
                        new CodingModelControls.ResponseModeControl(
                                response.kind(),
                                response.visible(),
                                response.readOnly(),
                                response.allowedValues().stream()
                                        .map(CodingResponseMode::valueOf)
                                        .toList(),
                                CodingResponseMode.valueOf(response.recommendedValue()),
                                response.effectiveSummary()),
                        new CodingModelControls.ReasoningEffortControl(
                                effort.kind(),
                                effort.visible(),
                                effort.readOnly(),
                                effort.allowedValues().stream()
                                        .map(ModelReasoningEffort::valueOf)
                                        .toList(),
                                ModelReasoningEffort.valueOf(effort.recommendedValue()),
                                effort.effectiveSummary())),
                new CodingModelPreferences(
                        CodingResponseMode.valueOf(value.recommendedResponseMode()),
                        Optional.ofNullable(value.recommendedEffort()).map(ModelReasoningEffort::valueOf)),
                Optional.ofNullable(value.imageInput()).map(TerminalProductFacts::image));
    }

    private static ImageInputProfile image(ImageProfile value) {
        return new ImageInputProfile(
                value.allowedSources().stream().map(ModelImageSource::valueOf).collect(Collectors.toUnmodifiableSet()),
                Set.copyOf(value.supportedMediaTypes()),
                Math.toIntExact(value.maxImagesPerRequest()),
                value.maxBytesPerItem(),
                value.maxTotalBytes(),
                Math.toIntExact(value.maxUrlCharacters()),
                value.detailSupported(),
                value.allowedDetails().stream().map(ModelImageDetail::valueOf).collect(Collectors.toUnmodifiableSet()));
    }

    static CodingShellPlan plan(ShellPlan value) {
        return new CodingShellPlan(
                value.token(),
                new AgentSessionId(value.sessionId()),
                value.safeCommand(),
                value.includeInContext(),
                CodingShellPlan.State.valueOf(value.state()),
                value.reasonCode());
    }

    static CodingShellExecution shell(ShellExecution value) {
        return new CodingShellExecution(
                value.id(),
                new AgentSessionId(value.sessionId()),
                value.acceptedAtEpochMillis(),
                CodingShellExecution.State.valueOf(value.state()),
                value.cancellationRequested(),
                Optional.ofNullable(value.result()).map(TerminalProductFacts::shellResult),
                Optional.ofNullable(value.errorCode()));
    }

    static CodingShellResult shellResult(ShellResult value) {
        return new CodingShellResult(
                value.processState(),
                Optional.ofNullable(value.exitCode()).map(Math::toIntExact),
                value.safeSummary(),
                Optional.ofNullable(value.outputReference()),
                value.truncated(),
                value.includedInContext());
    }
}
