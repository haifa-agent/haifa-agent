package io.haifa.agent.model.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, provider-neutral IO modality profile for one exact model binding.
 */
public record ModelIoProfile(
        Set<ModelInputModality> inputModalities,
        Set<ModelOutputModality> outputModalities,
        Optional<ImageInputProfile> imageInput) {

    public ModelIoProfile {
        inputModalities = Set.copyOf(Objects.requireNonNull(inputModalities, "inputModalities must not be null"));
        outputModalities = Set.copyOf(Objects.requireNonNull(outputModalities, "outputModalities must not be null"));
        imageInput = Objects.requireNonNull(imageInput, "imageInput must not be null");
        if (!inputModalities.contains(ModelInputModality.TEXT)) {
            throw new IllegalArgumentException("inputModalities must contain TEXT");
        }
        if (!outputModalities.contains(ModelOutputModality.TEXT)) {
            throw new IllegalArgumentException("outputModalities must contain TEXT");
        }
        if (imageInput.isPresent() && !inputModalities.contains(ModelInputModality.IMAGE)) {
            throw new IllegalArgumentException("imageInput present requires inputModalities to contain IMAGE");
        }
        if (imageInput.isEmpty() && inputModalities.contains(ModelInputModality.IMAGE)) {
            throw new IllegalArgumentException("inputModalities contains IMAGE but imageInput is missing");
        }
    }

    public static ModelIoProfile textOnly() {
        return new ModelIoProfile(Set.of(ModelInputModality.TEXT), Set.of(ModelOutputModality.TEXT), Optional.empty());
    }

    public static ModelIoProfile withImage(ImageInputProfile imageInput) {
        return new ModelIoProfile(
                Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE),
                Set.of(ModelOutputModality.TEXT),
                Optional.of(Objects.requireNonNull(imageInput, "imageInput must not be null")));
    }

    public boolean imageOutputSupported() {
        return false;
    }
}
