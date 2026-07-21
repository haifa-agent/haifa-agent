package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.agent.AgentCapabilityRequirement;
import io.haifa.agent.runtime.api.AgentRunRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DefaultCapabilityResolver implements CapabilityResolver {
    @Override
    public List<EffectiveCapability> resolve(
            AgentRunRequest request, ResolvedDefinition definition, ResolvedProfile profile) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        List<EffectiveCapability> effective = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AgentCapabilityRequirement requirement : definition.capabilityRequirements()) {
            if (!seen.add(requirement.capabilityId())) {
                throw new IllegalArgumentException("duplicate capability requirement: " + requirement.capabilityId());
            }
            ResolvedCapability candidate = profile.capabilities().get(requirement.capabilityId());
            if (candidate == null) {
                if (requirement.required()) {
                    throw failure(
                            CapabilityResolutionErrorCode.MISSING_REQUIRED_CAPABILITY,
                            requirement,
                            "required capability is not enabled");
                }
                continue;
            }
            if (!candidate.authorized()) {
                if (requirement.required()) {
                    throw failure(
                            CapabilityResolutionErrorCode.UNAUTHORIZED,
                            requirement,
                            "required capability is not authorized");
                }
                continue;
            }
            if (!matches(requirement.versionConstraint(), candidate.version())) {
                if (requirement.required()) {
                    throw failure(
                            CapabilityResolutionErrorCode.VERSION_INCOMPATIBLE,
                            requirement,
                            "required capability version is incompatible");
                }
                continue;
            }
            if (requiresProject(requirement.capabilityId()) && request.project().isEmpty()) {
                if (requirement.required()) {
                    throw failure(
                            CapabilityResolutionErrorCode.BINDING_UNAVAILABLE,
                            requirement,
                            "required project binding is unavailable");
                }
                continue;
            }
            if (requiresWorkspaceBinding(requirement.capabilityId()) && candidate.bindingRef() == null) {
                if (requirement.required()) {
                    throw failure(
                            CapabilityResolutionErrorCode.BINDING_UNAVAILABLE,
                            requirement,
                            "required workspace binding is unavailable");
                }
                continue;
            }
            effective.add(new EffectiveCapability(
                    candidate.capabilityId(),
                    candidate.version(),
                    candidate.bindingRef(),
                    candidate.configurationDigest()));
        }
        return effective.stream().sorted().toList();
    }

    private static boolean requiresProject(String capabilityId) {
        return capabilityId.equals("project") || capabilityId.startsWith("workspace.");
    }

    private static boolean requiresWorkspaceBinding(String capabilityId) {
        return capabilityId.startsWith("workspace.");
    }

    static boolean matches(String constraint, String version) {
        if (constraint.equals("*")) return true;
        if (constraint.endsWith(".*")) return version.startsWith(constraint.substring(0, constraint.length() - 1));
        if (constraint.startsWith(">=")) return compareVersions(version, constraint.substring(2)) >= 0;
        return constraint.equals(version);
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int size = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < size; index++) {
            int leftValue = index < leftParts.length ? parse(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parse(rightParts[index]) : 0;
            int comparison = Integer.compare(leftValue, rightValue);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("capability version must be numeric for range constraints");
        }
    }

    private static CapabilityResolutionException failure(
            CapabilityResolutionErrorCode code, AgentCapabilityRequirement requirement, String message) {
        return new CapabilityResolutionException(code, requirement.capabilityId(), message);
    }
}
