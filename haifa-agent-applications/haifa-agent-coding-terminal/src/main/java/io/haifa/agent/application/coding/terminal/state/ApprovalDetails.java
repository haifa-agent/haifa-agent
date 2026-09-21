package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Safe, structured approval projection. No field is inferred from display text. */
public record ApprovalDetails(
        String title,
        String purpose,
        String contentType,
        String content,
        List<Fact> environment,
        List<Fact> technical,
        Optional<String> risk,
        List<String> allowedActions) {

    /** One bounded label/value display fact. */
    public record Fact(String label, String value) {
        public Fact {
            label = require(label, "label");
            value = require(value, "value");
        }
    }

    public ApprovalDetails {
        title = require(title, "title");
        purpose = require(purpose, "purpose");
        contentType = require(contentType, "contentType");
        content = require(content, "content");
        environment = List.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        technical = List.copyOf(Objects.requireNonNull(technical, "technical must not be null"));
        risk = Objects.requireNonNull(risk, "risk must not be null");
        allowedActions = List.copyOf(Objects.requireNonNull(allowedActions, "allowedActions must not be null"));
    }

    public static ApprovalDetails from(InteractionView interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        List<String> allowedActions = interaction.allowedActions().stream()
                .map(InteractionAction::value)
                .toList();
        var presentation = interaction.approvalPresentation();
        if (presentation.isPresent()) {
            var value = presentation.orElseThrow();
            return new ApprovalDetails(
                    value.title(),
                    value.purpose(),
                    value.contentType(),
                    value.content(),
                    value.environment().stream()
                            .map(fact -> new Fact(fact.label(), fact.value()))
                            .toList(),
                    value.technical().stream()
                            .map(fact -> new Fact(fact.label(), fact.value()))
                            .toList(),
                    value.risk(),
                    allowedActions);
        }
        return new ApprovalDetails(
                interaction.title(),
                interaction.consequences().accepted(),
                "内容",
                interaction.safePrompt(),
                List.of(),
                List.of(),
                Optional.empty(),
                allowedActions);
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
