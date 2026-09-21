package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.runtime.api.ApprovalPresentation;
import java.util.List;
import java.util.Optional;

/**
 * Persisted, display-only approval presentation. Kept as a dedicated payload DTO so the stored shape is
 * stable and independent from the public runtime types.
 */
public record ApprovalPresentationPayload(
        String title,
        String purpose,
        String contentType,
        String content,
        List<FactPayload> environment,
        List<FactPayload> technical,
        String risk) {

    public record FactPayload(String label, String value) {}

    public static ApprovalPresentationPayload from(ApprovalPresentation presentation) {
        return new ApprovalPresentationPayload(
                presentation.title(),
                presentation.purpose(),
                presentation.contentType(),
                presentation.content(),
                presentation.environment().stream()
                        .map(fact -> new FactPayload(fact.label(), fact.value()))
                        .toList(),
                presentation.technical().stream()
                        .map(fact -> new FactPayload(fact.label(), fact.value()))
                        .toList(),
                presentation.risk().orElse(null));
    }

    public ApprovalPresentation toDomain() {
        return new ApprovalPresentation(
                title,
                purpose,
                contentType,
                content,
                environment.stream()
                        .map(fact -> new ApprovalPresentation.Fact(fact.label(), fact.value()))
                        .toList(),
                technical.stream()
                        .map(fact -> new ApprovalPresentation.Fact(fact.label(), fact.value()))
                        .toList(),
                Optional.ofNullable(risk));
    }
}
