package io.haifa.coding.host.internal;

import io.haifa.agent.application.project.product.coding.client.CodingApprovalPrompt;
import io.haifa.agent.application.project.product.coding.client.CodingSessionApprovalStatus;
import io.haifa.agent.application.project.product.coding.client.CodingSessionApprovals;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Transient consent per initialized client; expiry never transfers consent to a replacement lease. */
public final class CodingHostApprovals implements AutoCloseable {
    public static final String CAPABILITY = "session-consent-v1";
    public static final Duration LEASE_DURATION = Duration.ofSeconds(45);
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final AgentRuntime runtime;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final Duration duration;

    public CodingHostApprovals(AgentRuntime runtime, IdentifierGenerator identifiers, TimeProvider time) {
        this(runtime, identifiers, time, LEASE_DURATION);
    }

    public CodingHostApprovals(
            AgentRuntime runtime, IdentifierGenerator identifiers, TimeProvider time, Duration duration) {
        this.runtime = runtime;
        this.identifiers = identifiers;
        this.time = time;
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Lease must be positive");
        this.duration = duration;
    }

    public void initialize(String clientId) {
        leases.put(clientId, new Lease());
    }

    public void renew(String clientId) {
        leases.computeIfPresent(clientId, (ignored, previous) -> previous.renew() ? previous : new Lease());
    }

    private Lease lease(String clientId) {
        Lease result =
                leases.computeIfPresent(clientId, (ignored, previous) -> previous.valid() ? previous : new Lease());
        if (result == null) throw CodingHostException.unsupported("Session consent was not negotiated");
        return result;
    }

    public Optional<CodingApprovalPrompt> prepare(String clientId, InteractionView pending) {
        if (!leases.containsKey(clientId))
            return Optional.of(
                    new CodingApprovalPrompt(pending, false, Optional.of("REMOTE_SESSION_CONSENT_UNSUPPORTED")));
        return lease(clientId)
                .approvals
                .prepare(
                        pending,
                        interaction -> submission(
                                interaction,
                                "session-approval:" + interaction.requestId().value()));
    }

    public InteractionResponseReceipt grant(String clientId, InteractionView pending, String idempotencyKey) {
        return lease(clientId).approvals.approve(pending, submission(pending, idempotencyKey));
    }

    public void reset(AgentSessionId sessionId) {
        // A deliberate Session reset revokes consent from every attached client.
        leases.values().forEach(lease -> lease.approvals.reset(sessionId));
    }

    /** Read-only projection: an observation must never replace or extend an expired lease. */
    public Optional<CodingSessionApprovalStatus> presentationStatus(String clientId, AgentSessionId sessionId) {
        Lease lease = leases.get(clientId);
        if (lease == null || !lease.valid()) return Optional.empty();
        var state = lease.approvals.state(sessionId);
        if (leases.get(clientId) != lease || !lease.valid()) return Optional.empty();
        return Optional.of(new CodingSessionApprovalStatus(
                state,
                state == CodingSessionApprovalStatus.State.ASK ? Optional.empty() : Optional.of(lease.expiresAt)));
    }

    public CodingSessionApprovalStatus status(String clientId, AgentSessionId sessionId) {
        Lease lease = lease(clientId);
        var state = lease.approvals.state(sessionId);
        return new CodingSessionApprovalStatus(
                state,
                state == CodingSessionApprovalStatus.State.ASK ? Optional.empty() : Optional.of(lease.expiresAt));
    }

    private InteractionResponseSubmission submission(InteractionView pending, String key) {
        return new InteractionResponseSubmission(
                new InteractionResponseId(identifiers.nextValue()),
                pending.requestId(),
                pending.runId(),
                pending.revision(),
                InteractionAction.APPROVE,
                List.of(),
                key,
                time.now());
    }

    public void closeClient(String clientId) {
        Lease lease = leases.remove(clientId);
        if (lease != null) lease.revoke();
    }

    @Override
    public void close() {
        leases.values().forEach(Lease::revoke);
        leases.clear();
    }

    private final class Lease {
        volatile Instant expiresAt = time.now().plus(duration);
        private boolean revoked;
        final CodingSessionApprovals approvals = new CodingSessionApprovals(runtime, this::valid);

        public synchronized boolean valid() {
            if (!time.now().isBefore(expiresAt)) revoked = true;
            return !revoked;
        }

        public synchronized boolean renew() {
            if (!valid()) return false;
            expiresAt = time.now().plus(duration);
            return true;
        }

        public synchronized void revoke() {
            revoked = true;
        }
    }
}
