package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialDefinition;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialException;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.credential.api.CredentialResolver;
import io.haifa.agent.credential.api.CredentialStore;
import io.haifa.agent.credential.api.SecretFunction;
import io.haifa.agent.credential.api.SecretRedactor;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Sole decryption route for callers; leases are capped at binding expiry and are unredacted once closed. */
public final class DefaultCredentialBroker implements CredentialBroker {
    private final Map<CredentialDefinitionId, CredentialDefinition> definitions;
    private final List<CredentialBinding> bindings;
    private final CredentialResolver resolver;
    private final CredentialStore store;
    private final SecretRedactor redactor;

    public DefaultCredentialBroker(
            Collection<CredentialDefinition> definitions,
            Collection<CredentialBinding> bindings,
            CredentialResolver resolver,
            CredentialStore store) {
        this.definitions = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(CredentialDefinition::id, Function.identity()));
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.store = Objects.requireNonNull(store, "store");
        this.redactor = new DefaultSecretRedactor();
    }

    @Override
    public CredentialLease issue(CredentialRequest request) {
        authorizeRequirement(
                request.requirement(), definitions.get(request.requirement().definitionId()));
        CredentialBinding binding = resolver.resolve(request, bindings);
        var leaseExpiry = binding.expiresAt()
                .filter(expiry -> expiry.isBefore(request.expiresAt()))
                .orElse(request.expiresAt());
        CredentialLease delegate =
                store.lease(binding.reference(), request.tenant(), binding.definitionId(), leaseExpiry);
        redactor.track(delegate);
        return new RedactionTrackedLease(delegate, () -> redactor.forget(delegate));
    }

    @Override
    public CredentialLease issue(CredentialOperationRequest request) {
        authorizeRequirement(
                request.requirement(), definitions.get(request.requirement().definitionId()));
        CredentialBinding binding = resolver.resolve(request, bindings);
        var leaseExpiry = binding.expiresAt()
                .filter(expiry -> expiry.isBefore(request.expiresAt()))
                .orElse(request.expiresAt());
        CredentialLease delegate =
                store.lease(binding.reference(), request.tenant(), binding.definitionId(), leaseExpiry);
        redactor.track(delegate);
        return new RedactionTrackedLease(delegate, () -> redactor.forget(delegate));
    }

    @Override
    public SecretRedactor redactor() {
        return redactor;
    }

    private static void authorizeRequirement(CredentialRequirement requirement, CredentialDefinition definition) {
        if (definition == null
                || !definition.allowedScopes().containsAll(requirement.scopes())
                || !definition.allowedExposureModes().contains(requirement.exposureMode())) {
            throw new CredentialException("credential requirement is not authorized by its definition");
        }
    }

    private static final class RedactionTrackedLease implements CredentialLease {
        private final CredentialLease delegate;
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RedactionTrackedLease(CredentialLease delegate, Runnable onClose) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.onClose = Objects.requireNonNull(onClose, "onClose");
        }

        @Override
        public CredentialReference reference() {
            return delegate.reference();
        }

        @Override
        public Instant expiresAt() {
            return delegate.expiresAt();
        }

        @Override
        public boolean isClosed() {
            return closed.get() || delegate.isClosed();
        }

        @Override
        public <T> T use(SecretFunction<T> action) {
            if (closed.get()) throw new IllegalStateException("credential lease is closed");
            return delegate.use(action);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                delegate.close();
            } finally {
                onClose.run();
            }
        }
    }
}
