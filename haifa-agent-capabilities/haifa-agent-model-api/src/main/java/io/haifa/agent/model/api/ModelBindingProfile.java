package io.haifa.agent.model.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Provider-neutral, versioned limits for one exact model binding. */
public record ModelBindingProfile(
        ModelDefinitionId bindingId,
        ApiStyleId apiStyle,
        String version,
        Set<ModelCapability> capabilities,
        ModelReasoningBehavior reasoningBehavior,
        Set<ModelReasoningMode> allowedReasoningModes,
        Set<ModelReasoningEffort> allowedReasoningEfforts,
        OptionalLong maximumReasoningTokens,
        int minimumOutputTokens,
        int maximumOutputTokens,
        boolean toolReasoningContinuationRequired,
        ModelProfileStatus status,
        LocalDate lastVerifiedOn,
        String digest) {

    public ModelBindingProfile {
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        apiStyle = Objects.requireNonNull(apiStyle, "apiStyle must not be null");
        version = ModelValues.text(version, "version");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (capabilities.isEmpty()) throw new IllegalArgumentException("capabilities must not be empty");
        reasoningBehavior = Objects.requireNonNull(reasoningBehavior, "reasoningBehavior must not be null");
        allowedReasoningModes =
                Set.copyOf(Objects.requireNonNull(allowedReasoningModes, "allowedReasoningModes must not be null"));
        allowedReasoningEfforts =
                Set.copyOf(Objects.requireNonNull(allowedReasoningEfforts, "allowedReasoningEfforts must not be null"));
        maximumReasoningTokens =
                Objects.requireNonNull(maximumReasoningTokens, "maximumReasoningTokens must not be null");
        if (maximumReasoningTokens.isPresent() && maximumReasoningTokens.getAsLong() < 1) {
            throw new IllegalArgumentException("maximumReasoningTokens must be positive");
        }
        if (allowedReasoningModes.isEmpty()) {
            throw new IllegalArgumentException("allowedReasoningModes must not be empty");
        }
        if (minimumOutputTokens < 1
                || maximumOutputTokens < minimumOutputTokens
                || maximumOutputTokens > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("profile output token limits are invalid");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        lastVerifiedOn = Objects.requireNonNull(lastVerifiedOn, "lastVerifiedOn must not be null");
        validateReasoning(
                capabilities,
                reasoningBehavior,
                allowedReasoningModes,
                allowedReasoningEfforts,
                toolReasoningContinuationRequired);
        digest = ModelValues.text(digest, "digest");
        String expected = digest(
                bindingId,
                apiStyle,
                version,
                capabilities,
                reasoningBehavior,
                allowedReasoningModes,
                allowedReasoningEfforts,
                maximumReasoningTokens,
                minimumOutputTokens,
                maximumOutputTokens,
                toolReasoningContinuationRequired,
                status,
                lastVerifiedOn);
        if (!expected.equals(digest)) {
            throw new IllegalArgumentException("model profile digest does not match profile fields");
        }
    }

    public static ModelBindingProfile create(
            ModelDefinitionId bindingId,
            ApiStyleId apiStyle,
            String version,
            Set<ModelCapability> capabilities,
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            OptionalLong maximumReasoningTokens,
            int minimumOutputTokens,
            int maximumOutputTokens,
            boolean toolReasoningContinuationRequired,
            ModelProfileStatus status,
            LocalDate lastVerifiedOn) {
        Set<ModelCapability> frozenCapabilities = Set.copyOf(capabilities);
        Set<ModelReasoningMode> frozenModes = Set.copyOf(allowedReasoningModes);
        Set<ModelReasoningEffort> frozenEfforts = Set.copyOf(allowedReasoningEfforts);
        return new ModelBindingProfile(
                bindingId,
                apiStyle,
                version,
                frozenCapabilities,
                reasoningBehavior,
                frozenModes,
                frozenEfforts,
                maximumReasoningTokens,
                minimumOutputTokens,
                maximumOutputTokens,
                toolReasoningContinuationRequired,
                status,
                lastVerifiedOn,
                digest(
                        bindingId,
                        apiStyle,
                        version,
                        frozenCapabilities,
                        reasoningBehavior,
                        frozenModes,
                        frozenEfforts,
                        maximumReasoningTokens,
                        minimumOutputTokens,
                        maximumOutputTokens,
                        toolReasoningContinuationRequired,
                        status,
                        lastVerifiedOn));
    }

    public boolean selectable() {
        return status == ModelProfileStatus.VERIFIED;
    }

    private static void validateReasoning(
            Set<ModelCapability> capabilities,
            ModelReasoningBehavior behavior,
            Set<ModelReasoningMode> modes,
            Set<ModelReasoningEffort> efforts,
            boolean toolContinuationRequired) {
        boolean reasoning = capabilities.contains(ModelCapability.REASONING);
        if (!reasoning || behavior == ModelReasoningBehavior.NONE) {
            if (reasoning
                    || behavior != ModelReasoningBehavior.NONE
                    || !modes.equals(Set.of(ModelReasoningMode.DISABLED))) {
                throw new IllegalArgumentException("non-reasoning profile must allow disabled reasoning only");
            }
            if (!efforts.isEmpty() || toolContinuationRequired) {
                throw new IllegalArgumentException("non-reasoning profile cannot declare reasoning extensions");
            }
            return;
        }
        switch (behavior) {
            case OPTIONAL -> {
                if (!modes.containsAll(Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED))) {
                    throw new IllegalArgumentException("optional reasoning must allow disabled and enabled modes");
                }
            }
            case ALWAYS -> {
                if (!modes.equals(Set.of(ModelReasoningMode.ENABLED))) {
                    throw new IllegalArgumentException("always reasoning must allow enabled mode only");
                }
            }
            case ADAPTIVE -> {
                if (!modes.contains(ModelReasoningMode.ADAPTIVE)) {
                    throw new IllegalArgumentException("adaptive reasoning must allow adaptive mode");
                }
            }
            case NONE -> throw new IllegalArgumentException("reasoning capability cannot use NONE behavior");
        }
        if (efforts.isEmpty()) {
            throw new IllegalArgumentException("reasoning profile must declare at least one effort");
        }
    }

    public String canonicalString() {
        return canonicalString(
                bindingId,
                apiStyle,
                version,
                capabilities,
                reasoningBehavior,
                allowedReasoningModes,
                allowedReasoningEfforts,
                maximumReasoningTokens,
                minimumOutputTokens,
                maximumOutputTokens,
                toolReasoningContinuationRequired,
                status,
                lastVerifiedOn);
    }

    public static String canonicalString(
            ModelDefinitionId bindingId,
            ApiStyleId apiStyle,
            String version,
            Set<ModelCapability> capabilities,
            ModelReasoningBehavior behavior,
            Set<ModelReasoningMode> modes,
            Set<ModelReasoningEffort> efforts,
            OptionalLong maximumReasoningTokens,
            int minimumOutputTokens,
            int maximumOutputTokens,
            boolean toolContinuationRequired,
            ModelProfileStatus status,
            LocalDate lastVerifiedOn) {
        String canonicalBindingId = canonicalSegment(
                Objects.requireNonNull(bindingId, "bindingId must not be null").value(), "bindingId");
        String canonicalApiStyle = canonicalSegment(
                Objects.requireNonNull(apiStyle, "apiStyle must not be null").value(), "apiStyle");
        String canonicalVersion = canonicalSegment(ModelValues.text(version, "version"), "version");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(behavior, "behavior must not be null");
        Objects.requireNonNull(modes, "modes must not be null");
        Objects.requireNonNull(efforts, "efforts must not be null");
        Objects.requireNonNull(maximumReasoningTokens, "maximumReasoningTokens must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lastVerifiedOn, "lastVerifiedOn must not be null");
        return String.join(
                "|",
                "model-binding-profile-v1",
                canonicalBindingId,
                canonicalApiStyle,
                canonicalVersion,
                capabilities.stream().map(Enum::name).sorted().toList().toString(),
                behavior.name(),
                modes.stream().map(Enum::name).sorted().toList().toString(),
                efforts.stream().map(Enum::name).sorted().toList().toString(),
                maximumReasoningTokens.isPresent() ? Long.toString(maximumReasoningTokens.getAsLong()) : "none",
                Integer.toString(minimumOutputTokens),
                Integer.toString(maximumOutputTokens),
                Boolean.toString(toolContinuationRequired),
                status.name(),
                lastVerifiedOn.toString());
    }

    private static String canonicalSegment(String value, String field) {
        if (value.indexOf('|') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid for the model profile canonical form");
        }
        return value;
    }

    public static String digest(
            ModelDefinitionId bindingId,
            ApiStyleId apiStyle,
            String version,
            Set<ModelCapability> capabilities,
            ModelReasoningBehavior behavior,
            Set<ModelReasoningMode> modes,
            Set<ModelReasoningEffort> efforts,
            OptionalLong maximumReasoningTokens,
            int minimumOutputTokens,
            int maximumOutputTokens,
            boolean toolContinuationRequired,
            ModelProfileStatus status,
            LocalDate lastVerifiedOn) {
        String canonical = canonicalString(
                bindingId,
                apiStyle,
                version,
                capabilities,
                behavior,
                modes,
                efforts,
                maximumReasoningTokens,
                minimumOutputTokens,
                maximumOutputTokens,
                toolContinuationRequired,
                status,
                lastVerifiedOn);
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
