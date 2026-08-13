package io.haifa.agent.model.core;

import io.haifa.agent.model.api.EffectiveModelParameters;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelParameterResolutionException;
import io.haifa.agent.model.api.ModelParameterResolutionFailure;
import io.haifa.agent.model.api.ModelParameterResolutionRequest;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import java.util.Objects;

/** Validates product preferences against an exact binding profile and freezes effective parameters. */
public final class DefaultModelParameterResolver {

    public EffectiveModelParameters resolve(ModelBindingProfile profile, ModelParameterResolutionRequest request) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!profile.selectable()) fail(ModelParameterResolutionFailure.PROFILE_UNAVAILABLE);
        if (!profile.bindingId().equals(request.bindingId())) {
            fail(ModelParameterResolutionFailure.BINDING_MISMATCH);
        }
        if (!profile.version().equals(request.profileVersion())
                || !profile.digest().equals(request.profileDigest())) {
            fail(ModelParameterResolutionFailure.PROFILE_STALE);
        }
        ModelReasoningPolicy reasoning = request.reasoning();
        if (!profile.allowedReasoningModes().contains(reasoning.mode())) {
            fail(ModelParameterResolutionFailure.REASONING_MODE_UNSUPPORTED);
        }
        if (profile.reasoningBehavior() == ModelReasoningBehavior.ALWAYS
                && reasoning.mode() == ModelReasoningMode.DISABLED) {
            fail(ModelParameterResolutionFailure.REASONING_MODE_UNSUPPORTED);
        }
        reasoning.effort().ifPresent(effort -> {
            if (!profile.allowedReasoningEfforts().contains(effort)) {
                fail(ModelParameterResolutionFailure.REASONING_EFFORT_UNSUPPORTED);
            }
        });
        if (reasoning.mode() != ModelReasoningMode.DISABLED
                && !profile.allowedReasoningEfforts().isEmpty()
                && reasoning.effort().isEmpty()) {
            fail(ModelParameterResolutionFailure.REASONING_EFFORT_REQUIRED);
        }
        if (reasoning.tokenBudget().isPresent()) {
            if (profile.maximumReasoningTokens().isEmpty()
                    || reasoning.tokenBudget().getAsLong()
                            > profile.maximumReasoningTokens().getAsLong()) {
                fail(ModelParameterResolutionFailure.REASONING_BUDGET_UNSUPPORTED);
            }
        }
        if (request.maxOutputTokens() < profile.minimumOutputTokens()
                || request.maxOutputTokens() > profile.maximumOutputTokens()) {
            fail(ModelParameterResolutionFailure.OUTPUT_LIMIT_UNSUPPORTED);
        }
        return new EffectiveModelParameters(
                request.bindingId(), profile.version(), profile.digest(), reasoning, request.maxOutputTokens());
    }

    private static void fail(ModelParameterResolutionFailure failure) {
        throw new ModelParameterResolutionException(failure, "model parameter resolution failed: " + failure.name());
    }
}
