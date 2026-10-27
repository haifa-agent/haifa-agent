package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.api.ClarificationPresentation;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionInputType;
import io.haifa.agent.runtime.api.InteractionOption;
import io.haifa.agent.runtime.core.interaction.ClarificationTarget;
import io.haifa.agent.runtime.core.interaction.GenericInteractionTarget;
import io.haifa.agent.runtime.core.interaction.InteractionTarget;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Version 3 encoded interaction target payload. It extends version 2 with the display-only clarification
 * presentation and the per-question input contracts, so a restarted process can still validate answers.
 */
public record InteractionTargetPayloadV3(
        String kind,
        String type,
        String toolCallId,
        String coordinate,
        String definitionHash,
        String argumentsDigest,
        String principalScope,
        String requirementDigest,
        ApprovalPresentationPayload presentation,
        ClarificationPayload clarification) {

    public static InteractionTargetPayloadV3 from(
            InteractionTarget target,
            Optional<ApprovalPresentation> approvalPresentation,
            Optional<ClarificationPresentation> clarificationPresentation,
            List<InteractionInputContract> inputContracts) {
        ApprovalPresentationPayload encodedApproval =
                approvalPresentation.map(ApprovalPresentationPayload::from).orElse(null);
        ClarificationPayload encodedClarification = clarificationPresentation
                .map(presentation -> ClarificationPayload.from(presentation, inputContracts))
                .orElse(null);
        if (target instanceof GenericInteractionTarget generic) {
            return new InteractionTargetPayloadV3(
                    "generic",
                    generic.type(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    encodedApproval,
                    encodedClarification);
        }
        if (target instanceof ToolApprovalTarget tool) {
            return new InteractionTargetPayloadV3(
                    "tool-approval",
                    null,
                    tool.toolCallId().value(),
                    tool.coordinate(),
                    tool.definitionHash(),
                    tool.argumentsDigest(),
                    tool.principalScope(),
                    tool.requirementDigest(),
                    encodedApproval,
                    encodedClarification);
        }
        if (target instanceof ClarificationTarget clarification) {
            return new InteractionTargetPayloadV3(
                    "clarification",
                    null,
                    clarification.toolCallId().value(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    encodedApproval,
                    encodedClarification);
        }
        throw new IllegalArgumentException("unsupported interaction target");
    }

    public InteractionTarget toDomain() {
        return switch (kind) {
            case "generic" -> new GenericInteractionTarget(type);
            case "tool-approval" ->
                new ToolApprovalTarget(
                        new ToolCallId(toolCallId),
                        coordinate,
                        definitionHash,
                        argumentsDigest,
                        principalScope,
                        requirementDigest);
            case "clarification" -> new ClarificationTarget(new ToolCallId(toolCallId));
            default -> throw new IllegalStateException("unknown interaction target kind");
        };
    }

    public Optional<ApprovalPresentation> presentationDomain() {
        return Optional.ofNullable(presentation).map(ApprovalPresentationPayload::toDomain);
    }

    public Optional<ClarificationPresentation> clarificationDomain() {
        return Optional.ofNullable(clarification).map(ClarificationPayload::toDomain);
    }

    public List<InteractionInputContract> contractsDomain() {
        return clarification == null ? List.of() : clarification.contractsDomain();
    }

    public record ClarificationPayload(String title, List<QuestionPayload> questions, List<ContractPayload> contracts) {

        static ClarificationPayload from(
                ClarificationPresentation presentation, List<InteractionInputContract> contracts) {
            return new ClarificationPayload(
                    presentation.title(),
                    presentation.questions().stream().map(QuestionPayload::from).toList(),
                    contracts.stream().map(ContractPayload::from).toList());
        }

        ClarificationPresentation toDomain() {
            return new ClarificationPresentation(
                    title, questions.stream().map(QuestionPayload::toDomain).toList());
        }

        List<InteractionInputContract> contractsDomain() {
            List<InteractionInputContract> decoded = new ArrayList<>();
            for (ContractPayload contract : contracts) decoded.add(contract.toDomain());
            return decoded;
        }
    }

    public record QuestionPayload(
            String header, String question, boolean multiple, boolean allowCustom, List<OptionPayload> options) {

        static QuestionPayload from(ClarificationPresentation.Question question) {
            return new QuestionPayload(
                    question.header(),
                    question.question(),
                    question.multiple(),
                    question.allowCustom(),
                    question.options().stream().map(OptionPayload::from).toList());
        }

        ClarificationPresentation.Question toDomain() {
            return new ClarificationPresentation.Question(
                    header,
                    question,
                    multiple,
                    allowCustom,
                    options.stream().map(OptionPayload::toDomainPresented).toList());
        }
    }

    public record OptionPayload(String id, String label, String description) {

        static OptionPayload from(InteractionOption option) {
            return new OptionPayload(option.id(), option.label(), null);
        }

        static OptionPayload from(ClarificationPresentation.Option option) {
            return new OptionPayload(
                    option.optionId(), option.label(), option.description().orElse(null));
        }

        InteractionOption toDomain() {
            return new InteractionOption(id, label);
        }

        ClarificationPresentation.Option toDomainPresented() {
            return new ClarificationPresentation.Option(id, label, Optional.ofNullable(description));
        }
    }

    public record ContractPayload(
            String type,
            int maximumCharacters,
            int minimumSelections,
            int maximumSelections,
            List<OptionPayload> options) {

        static ContractPayload from(InteractionInputContract contract) {
            return new ContractPayload(
                    contract.type().value(),
                    contract.maximumCharacters(),
                    contract.minimumSelections(),
                    contract.maximumSelections(),
                    contract.options().stream().map(OptionPayload::from).toList());
        }

        InteractionInputContract toDomain() {
            return new InteractionInputContract(
                    new InteractionInputType(type),
                    maximumCharacters,
                    minimumSelections,
                    maximumSelections,
                    0,
                    0,
                    options.stream().map(OptionPayload::toDomain).toList(),
                    Optional.empty());
        }
    }
}
