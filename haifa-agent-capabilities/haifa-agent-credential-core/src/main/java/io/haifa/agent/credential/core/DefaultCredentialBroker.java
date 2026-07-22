package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialBinding;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialDefinition;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialException;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialResolver;
import io.haifa.agent.credential.api.CredentialStore;
import io.haifa.agent.credential.api.CredentialUsageAudit;
import io.haifa.agent.credential.api.CredentialUsageAuditSink;
import io.haifa.agent.credential.api.CredentialUsagePhase;
import io.haifa.agent.credential.api.SecretRedactor;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultCredentialBroker implements CredentialBroker {
    private final Map<CredentialDefinitionId, CredentialDefinition> definitions;
    private final Collection<CredentialBinding> bindings;
    private final CredentialResolver resolver;
    private final CredentialStore store;
    private final CredentialUsageAuditSink audit;
    private final Clock clock;
    private final SecretRedactor redactor;

    public DefaultCredentialBroker(
            Collection<CredentialDefinition> definitions,
            Collection<CredentialBinding> bindings,
            CredentialResolver resolver,
            CredentialStore store) {
        this(
                definitions,
                bindings,
                resolver,
                store,
                CredentialUsageAuditSink.noop(),
                Clock.systemUTC(),
                new DefaultSecretRedactor());
    }

    public DefaultCredentialBroker(
            Collection<CredentialDefinition> definitions,
            Collection<CredentialBinding> bindings,
            CredentialResolver resolver,
            CredentialStore store,
            CredentialUsageAuditSink audit) {
        this(definitions, bindings, resolver, store, audit, Clock.systemUTC(), new DefaultSecretRedactor());
    }

    public DefaultCredentialBroker(
            Collection<CredentialDefinition> definitions,
            Collection<CredentialBinding> bindings,
            CredentialResolver resolver,
            CredentialStore store,
            CredentialUsageAuditSink audit,
            Clock clock) {
        this(definitions, bindings, resolver, store, audit, clock, new DefaultSecretRedactor());
    }

    public DefaultCredentialBroker(
            Collection<CredentialDefinition> definitions,
            Collection<CredentialBinding> bindings,
            CredentialResolver resolver,
            CredentialStore store,
            CredentialUsageAuditSink audit,
            Clock clock,
            SecretRedactor redactor) {
        this.definitions = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(CredentialDefinition::id, Function.identity()));
        this.bindings = ListCopies.copyOf(bindings);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    @Override
    public CredentialLease issue(CredentialRequest request) {
        CredentialDefinition definition = definitions.get(request.requirement().definitionId());
        if (definition == null
                || !definition.allowedScopes().containsAll(request.requirement().scopes())
                || !definition
                        .allowedExposureModes()
                        .contains(request.requirement().exposureMode())) {
            throw new CredentialException("credential requirement is not authorized by its definition");
        }
        CredentialBinding binding = resolver.resolve(request, bindings);
        var leaseExpiry = binding.expiresAt()
                .filter(expiry -> expiry.isBefore(request.expiresAt()))
                .orElse(request.expiresAt());
        CredentialLease delegate =
                store.lease(binding.reference(), request.tenant(), binding.definitionId(), leaseExpiry);
        audit.record(event(request, binding, delegate, request.requestedAt(), CredentialUsagePhase.ISSUED));
        redactor.track(delegate);
        return new AuditedCredentialLease(delegate, () -> {
            redactor.forget(delegate);
            audit.record(event(request, binding, delegate, clock.instant(), CredentialUsagePhase.CLOSED));
        });
    }

    @Override
    public SecretRedactor redactor() {
        return redactor;
    }

    private static CredentialUsageAudit event(
            CredentialRequest request,
            CredentialBinding binding,
            CredentialLease lease,
            java.time.Instant occurredAt,
            CredentialUsagePhase phase) {
        return new CredentialUsageAudit(
                binding.reference(),
                binding.definitionId(),
                request.tenant(),
                request.principal(),
                request.runId(),
                request.toolCoordinate(),
                request.requirement().purpose(),
                occurredAt,
                lease.expiresAt(),
                phase);
    }

    private static final class AuditedCredentialLease implements CredentialLease {
        private final CredentialLease delegate;
        private final Runnable onClose;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private AuditedCredentialLease(CredentialLease delegate, Runnable onClose) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.onClose = Objects.requireNonNull(onClose, "onClose");
        }

        @Override
        public io.haifa.agent.credential.api.CredentialReference reference() {
            return delegate.reference();
        }

        @Override
        public java.time.Instant expiresAt() {
            return delegate.expiresAt();
        }

        @Override
        public boolean isClosed() {
            return closed.get() || delegate.isClosed();
        }

        @Override
        public <T> T use(io.haifa.agent.credential.api.SecretFunction<T> action) {
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

    private static final class ListCopies {
        private ListCopies() {}

        static <T> java.util.List<T> copyOf(Collection<T> values) {
            return java.util.List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }
}
