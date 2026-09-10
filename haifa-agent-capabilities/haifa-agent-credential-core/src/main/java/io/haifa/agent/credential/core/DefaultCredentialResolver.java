package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialException;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialResolver;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.credential.api.CredentialStatus;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultCredentialResolver implements CredentialResolver {
    private static final Map<CredentialScopeKind, Integer> PRECEDENCE = Map.of(
            CredentialScopeKind.SESSION, 0,
            CredentialScopeKind.PROJECT, 1,
            CredentialScopeKind.USER, 2,
            CredentialScopeKind.SYSTEM, 3);

    @Override
    public CredentialBinding resolve(CredentialRequest request, Collection<CredentialBinding> bindings) {
        Objects.requireNonNull(request, "request");
        return resolve(
                request.tenant(),
                request.principal(),
                request.requirement(),
                request.scopeChain(),
                request.requestedAt(),
                request.toolCoordinate(),
                bindings);
    }

    @Override
    public CredentialBinding resolve(CredentialOperationRequest request, Collection<CredentialBinding> bindings) {
        Objects.requireNonNull(request, "request");
        return resolve(
                request.tenant(),
                request.principal(),
                request.requirement(),
                request.scopeChain(),
                request.requestedAt(),
                request.targetBindingReference(),
                bindings);
    }

    private static CredentialBinding resolve(
            io.haifa.agent.core.reference.TenantRef tenant,
            io.haifa.agent.core.reference.PrincipalRef principal,
            io.haifa.agent.credential.api.CredentialRequirement requirement,
            List<io.haifa.agent.credential.api.CredentialBindingScope> scopeChain,
            java.time.Instant requestedAt,
            String target,
            Collection<CredentialBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<CredentialBinding> candidates = bindings.stream()
                .filter(binding -> authorized(tenant, principal, requirement, scopeChain, requestedAt, target, binding))
                .sorted(Comparator.comparingInt(
                        binding -> PRECEDENCE.get(binding.scope().kind())))
                .toList();
        if (candidates.isEmpty()) {
            throw new CredentialException("credential is unavailable");
        }
        int winningPrecedence = PRECEDENCE.get(candidates.getFirst().scope().kind());
        List<CredentialBinding> winners = candidates.stream()
                .filter(binding -> PRECEDENCE.get(binding.scope().kind()) == winningPrecedence)
                .toList();
        if (winners.size() != 1) {
            throw new CredentialException("credential binding is ambiguous");
        }
        return winners.getFirst();
    }

    private static boolean authorized(
            io.haifa.agent.core.reference.TenantRef tenant,
            io.haifa.agent.core.reference.PrincipalRef principal,
            io.haifa.agent.credential.api.CredentialRequirement requirement,
            List<io.haifa.agent.credential.api.CredentialBindingScope> scopeChain,
            java.time.Instant requestedAt,
            String target,
            CredentialBinding binding) {
        if (!binding.tenant().equals(tenant)
                || binding.status() != CredentialStatus.ACTIVE
                || !binding.definitionId().equals(requirement.definitionId())
                || binding.expiresAt()
                        .filter(expiry -> !expiry.isAfter(requestedAt))
                        .isPresent()
                || binding.principal().filter(value -> !value.equals(principal)).isPresent()) {
            return false;
        }
        if (!scopeChain.contains(binding.scope())) {
            return false;
        }
        return permits(binding.allowedToolCoordinates(), target)
                && permits(binding.allowedPurposes(), requirement.purpose())
                && binding.allowedScopes().containsAll(requirement.scopes())
                && binding.allowedExposureModes().contains(requirement.exposureMode());
    }

    private static boolean permits(Collection<String> allowed, String requested) {
        return allowed.isEmpty() || allowed.contains(requested);
    }
}
