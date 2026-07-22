package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialException;
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
            CredentialScopeKind.EXPLICIT_INVOCATION, 0,
            CredentialScopeKind.SESSION, 1,
            CredentialScopeKind.PROJECT, 2,
            CredentialScopeKind.USER, 3,
            CredentialScopeKind.ORGANIZATION, 4,
            CredentialScopeKind.SYSTEM, 5);

    @Override
    public CredentialBinding resolve(CredentialRequest request, Collection<CredentialBinding> bindings) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(bindings, "bindings");
        List<CredentialBinding> candidates = bindings.stream()
                .filter(binding -> authorized(request, binding))
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

    private static boolean authorized(CredentialRequest request, CredentialBinding binding) {
        if (!binding.tenant().equals(request.tenant())
                || binding.status() != CredentialStatus.ACTIVE
                || !binding.definitionId().equals(request.requirement().definitionId())
                || binding.expiresAt()
                        .filter(expiry -> !expiry.isAfter(request.requestedAt()))
                        .isPresent()
                || binding.principal()
                        .filter(principal -> !principal.equals(request.principal()))
                        .isPresent()) {
            return false;
        }
        if (request.explicitBindingId().isPresent()
                && !request.explicitBindingId().orElseThrow().equals(binding.bindingId())) {
            return false;
        }
        boolean inScopeChain = request.scopeChain().contains(binding.scope());
        if (!inScopeChain && binding.scope().kind() != CredentialScopeKind.EXPLICIT_INVOCATION) {
            return false;
        }
        return permits(binding.allowedToolCoordinates(), request.toolCoordinate())
                && permits(binding.allowedPurposes(), request.requirement().purpose())
                && binding.allowedScopes().containsAll(request.requirement().scopes())
                && binding.allowedExposureModes().contains(request.requirement().exposureMode());
    }

    private static boolean permits(Collection<String> allowed, String requested) {
        return allowed.isEmpty() || allowed.contains(requested);
    }
}
