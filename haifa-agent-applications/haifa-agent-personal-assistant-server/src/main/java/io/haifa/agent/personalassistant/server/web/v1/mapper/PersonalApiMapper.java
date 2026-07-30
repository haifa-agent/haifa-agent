package io.haifa.agent.personalassistant.server.web.v1.mapper;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import org.springframework.stereotype.Component;

@Component
public final class PersonalApiMapper {
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
                value.id(), value.role(), value.runId(), value.sequence(), value.text(), value.createdAt());
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
                value.runId(),
                value.kind().name(),
                value.displayName(),
                value.safeTargetSummary(),
                value.status(),
                value.startedAt(),
                value.completedAt(),
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
