package io.haifa.agent.contract.common;

import java.util.Optional;

public record ReferenceContentPartDto(String referenceType, String reference, Optional<String> mediaType)
        implements ContentPartDto {
    public ReferenceContentPartDto {
        referenceType = CorrelationId.requireText(referenceType, "referenceType", 64);
        reference = CorrelationId.requireText(reference, "reference", 512);
        mediaType = java.util.Objects.requireNonNull(mediaType, "mediaType must not be null")
                .map(value -> CorrelationId.requireText(value, "mediaType", 128));
    }

    @Override
    public String contentType() {
        return "reference";
    }
}
