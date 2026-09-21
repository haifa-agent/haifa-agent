package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Product-level, display-only projection of an approval request.
 *
 * <p>The projection is optional and never used for policy or execution decisions. Products supply it so
 * that graphical and terminal surfaces can render a concise, human-readable decision view instead of
 * parsing the formatted {@link InteractionView#safePrompt()} text. {@code safePrompt} remains the
 * authoritative fallback for non-GUI consumers.
 */
public record ApprovalPresentation(
        String title,
        String purpose,
        String contentType,
        String content,
        List<Fact> environment,
        List<Fact> technical,
        Optional<String> risk) {

    /** One bounded label/value display fact. */
    public record Fact(String label, String value) {
        public Fact {
            label = InteractionOption.requireText(label, "label", 64);
            value = InteractionOption.requireText(value, "value", 512);
        }
    }

    public ApprovalPresentation {
        title = InteractionOption.requireText(title, "title", 256);
        purpose = InteractionOption.requireText(purpose, "purpose", 512);
        contentType = InteractionOption.requireText(contentType, "contentType", 64);
        content = InteractionOption.requireText(content, "content", 16_384);
        environment = List.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        if (environment.size() > 8) {
            throw new IllegalArgumentException("environment must contain at most 8 facts");
        }
        technical = List.copyOf(Objects.requireNonNull(technical, "technical must not be null"));
        if (technical.size() > 16) {
            throw new IllegalArgumentException("technical must contain at most 16 facts");
        }
        risk = Objects.requireNonNull(risk, "risk must not be null")
                .map(value -> InteractionOption.requireText(value, "risk", 32));
    }
}
