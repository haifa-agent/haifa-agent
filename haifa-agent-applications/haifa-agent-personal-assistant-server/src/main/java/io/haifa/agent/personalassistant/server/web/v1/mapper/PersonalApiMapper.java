package io.haifa.agent.personalassistant.server.web.v1.mapper;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.mission.MissionSnapshot;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class PersonalApiMapper {
    public PersonalApiDtos.MissionSnapshot mission(MissionSnapshot value) {
        var constraints = new PersonalApiDtos.MissionConstraints(
                value.constraints().maxTasks(),
                value.constraints().maxDependencyDepth(),
                value.constraints().deadlineAt().orElse(null));
        Map<String, io.haifa.agent.personalassistant.application.mission.MissionTaskState> states =
                value.execution().tasks().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot
                                                .TaskExecution::taskId,
                                io.haifa.agent.personalassistant.application.mission.MissionExecutionSnapshot
                                                .TaskExecution::state));
        var plan = value.plan()
                .map(revision -> new PersonalApiDtos.MissionPlanRevision(
                        revision.revisionNo(),
                        revision.schemaId(),
                        revision.schemaVersion(),
                        revision.tasks().stream()
                                .map(task -> missionTask(task, states.get(task.taskId())))
                                .toList(),
                        revision.plannerSessionId(),
                        revision.plannerRunId(),
                        revision.createdAt()));
        List<PersonalApiDtos.MissionTask> tasks =
                plan.map(PersonalApiDtos.MissionPlanRevision::tasks).orElseGet(List::of);
        var execution = new PersonalApiDtos.MissionExecution(
                value.execution().dispatcherStatus(),
                value.execution().recovering(),
                value.execution().allTasksSettled(),
                value.execution().completedTasks(),
                value.execution().blockedTasks(),
                value.execution().currentTaskId(),
                value.execution()
                        .latestAttempt()
                        .map(attempt -> new PersonalApiDtos.MissionAttempt(
                                attempt.taskId(),
                                attempt.attemptNo(),
                                attempt.state().name(),
                                attempt.sessionId(),
                                attempt.runId(),
                                attempt.failureCode(),
                                attempt.updatedAt())));
        return new PersonalApiDtos.MissionSnapshot(
                value.schemaVersion(),
                value.missionId(),
                value.conversationId(),
                value.objective(),
                value.acceptanceCriteria(),
                constraints,
                value.state().name(),
                plan,
                tasks,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty(),
                value.version(),
                value.createdAt(),
                value.updatedAt(),
                value.confirmedAt(),
                value.finishedAt(),
                value.pollAfterMillis(),
                execution);
    }

    private PersonalApiDtos.MissionTask missionTask(
            io.haifa.agent.personalassistant.application.mission.MissionTask value,
            io.haifa.agent.personalassistant.application.mission.MissionTaskState executionState) {
        return new PersonalApiDtos.MissionTask(
                value.taskId(),
                value.ordinal(),
                value.title(),
                value.objective(),
                value.acceptanceCriteria(),
                value.dependsOn(),
                value.taskType(),
                value.requiredSkillIds().stream().sorted().toList(),
                value.resultSchemaId(),
                value.resultSchemaVersion(),
                (executionState == null ? value.state() : executionState).name());
    }

    public PersonalApiDtos.Conversation conversation(PersonalAssistantApplication.ConversationView value) {
        return new PersonalApiDtos.Conversation(
                value.id(),
                value.displayName(),
                value.status(),
                value.activeRunId(),
                value.createdAt(),
                value.lastActivityAt(),
                value.revision(),
                modelSelection(value.model()));
    }

    public PersonalApiDtos.Model model(io.haifa.agent.personalassistant.application.PersonalModelOption value) {
        return new PersonalApiDtos.Model(
                value.id(),
                value.displayName(),
                value.providerId(),
                value.providerDisplayName(),
                value.capabilities().stream().sorted().toList(),
                value.contextWindow());
    }

    public PersonalApiDtos.ModelSelection modelSelection(PersonalAssistantApplication.ModelSelectionView value) {
        return new PersonalApiDtos.ModelSelection(model(value.model()), value.revision(), value.available());
    }

    public PersonalApiDtos.Turn turn(PersonalAssistantApplication.TurnView value) {
        return new PersonalApiDtos.Turn(
                value.id(),
                value.role(),
                value.runId(),
                value.sequence(),
                value.text(),
                value.images().stream()
                        .map(image -> new PersonalApiDtos.TurnImage(
                                image.kind(),
                                image.url(),
                                image.imageId(),
                                image.mediaType(),
                                image.sizeBytes(),
                                image.originalFilename()))
                        .toList(),
                value.createdAt());
    }

    public PersonalApiDtos.Run run(PersonalAssistantApplication.RunView value) {
        var usage = value.usage();
        return new PersonalApiDtos.Run(
                value.id(),
                value.conversationId(),
                value.status(),
                value.version(),
                value.updatedAt(),
                value.output(),
                value.resultSummary(),
                value.errorCode(),
                value.error()
                        .map(error -> new PersonalApiDtos.ExecutionError(
                                error.code(),
                                error.message(),
                                error.category(),
                                error.retryability(),
                                error.details(),
                                error.diagnosticId(),
                                error.occurredAt())),
                value.plan()
                        .map(plan -> new PersonalApiDtos.Plan(
                                plan.id(),
                                plan.objective(),
                                plan.items().stream()
                                        .map(item -> new PersonalApiDtos.Todo(
                                                item.id(),
                                                item.title(),
                                                item.priority(),
                                                item.status(),
                                                item.startedAt(),
                                                item.completedAt()))
                                        .toList(),
                                plan.revision(),
                                plan.updatedAt())),
                new PersonalApiDtos.Usage(
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.totalTokens(),
                        usage.cachedInputTokens(),
                        usage.modelCalls(),
                        usage.toolCalls()));
    }

    public PersonalApiDtos.Activity activity(PersonalAssistantApplication.ActivityView value) {
        return new PersonalApiDtos.Activity(
                value.activityId(),
                value.eventId(),
                value.parentActivityId(),
                value.runId(),
                value.kind().name(),
                value.displayName(),
                value.safeTargetSummary(),
                value.status(),
                value.requestedAt(),
                value.startedAt(),
                value.completedAt(),
                value.occurredAt(),
                value.safeResultSummary(),
                value.interactionRef(),
                value.version());
    }

    public PersonalApiDtos.Interaction interaction(PersonalAssistantApplication.InteractionViewValue value) {
        return new PersonalApiDtos.Interaction(
                value.id(),
                value.runId(),
                value.conversationId(),
                value.revision(),
                value.kind(),
                value.state(),
                value.title(),
                value.safePrompt(),
                value.allowedActions(),
                value.inputType(),
                value.maximumCharacters(),
                value.createdAt(),
                value.expiresAt());
    }

    public PersonalApiDtos.InteractionReceipt receipt(PersonalAssistantApplication.InteractionReceipt value) {
        return new PersonalApiDtos.InteractionReceipt(
                value.responseId(),
                value.interactionId(),
                value.runId(),
                value.status(),
                value.interactionState(),
                value.revision(),
                value.runVersion());
    }

    public PersonalApiDtos.MemoryCandidate candidate(PersonalAssistantApplication.MemoryCandidateView value) {
        return new PersonalApiDtos.MemoryCandidate(
                value.id(),
                value.kind(),
                value.subjectKey(),
                value.content(),
                value.status(),
                value.updatedAt(),
                value.revision());
    }

    public PersonalApiDtos.Memory memory(PersonalAssistantApplication.MemoryView value) {
        return new PersonalApiDtos.Memory(
                value.id(),
                value.version(),
                value.kind(),
                value.subjectKey(),
                value.content(),
                value.status(),
                value.createdAt(),
                value.updatedAt());
    }

    public PersonalApiDtos.StreamEvent stream(PersonalAssistantApplication.StreamEvent value) {
        return new PersonalApiDtos.StreamEvent(
                value.id(),
                value.type(),
                value.runId(),
                value.occurredAt(),
                value.value(),
                value.activity().map(this::activity),
                value.source().name().toLowerCase(java.util.Locale.ROOT),
                value.sequence());
    }
}
