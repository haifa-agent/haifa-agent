package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public final class InMemoryCodingSessionStore implements CodingSessionStore {
    private final Map<String, CodingCommandBinding> commands = new LinkedHashMap<>();
    private final Map<AgentSessionId, CodingSessionActivity> activities = new LinkedHashMap<>();
    private final Map<String, CodingFollowUp> followUps = new LinkedHashMap<>();

    @Override
    public synchronized CodingCommandBinding reserveCommand(CodingCommandBinding candidate) {
        String key = commandKey(candidate);
        CodingCommandBinding existing = commands.get(key);
        if (existing != null) {
            if (!sameRequest(existing, candidate)) throw conflict("idempotency key is bound to another request");
            return existing;
        }
        commands.put(key, candidate);
        return candidate;
    }

    @Override
    public synchronized CodingCommandBinding completeCommand(String dispatchKey, AgentRunId runId) {
        var entry = commands.entrySet().stream()
                .filter(value -> value.getValue().dispatchKey().equals(dispatchKey))
                .findFirst()
                .orElseThrow(() -> conflict("coding command is unavailable"));
        CodingCommandBinding current = entry.getValue();
        if (current.runId().isPresent() && !current.runId().orElseThrow().equals(runId)) {
            throw conflict("coding command resolved to another Run");
        }
        CodingCommandBinding completed = new CodingCommandBinding(
                current.callerScopeDigest(),
                current.operation(),
                current.idempotencyKeyDigest(),
                current.requestDigest(),
                current.dispatchKey(),
                current.sessionId(),
                current.projectId(),
                current.message(),
                current.attachments(),
                Optional.of(runId),
                current.createdAt());
        entry.setValue(completed);
        return completed;
    }

    @Override
    public synchronized Optional<CodingCommandBinding> findCommandByDispatchKey(String dispatchKey) {
        return commands.values().stream()
                .filter(value -> value.dispatchKey().equals(dispatchKey))
                .findFirst();
    }

    @Override
    public synchronized CodingSessionActivity createActivity(CodingSessionActivity activity) {
        CodingSessionActivity existing = activities.putIfAbsent(activity.sessionId(), activity);
        if (existing != null && !existing.equals(activity)) throw conflict("coding session activity already exists");
        return existing == null ? activity : existing;
    }

    @Override
    public synchronized Optional<CodingSessionActivity> findActivity(AgentSessionId sessionId) {
        return Optional.ofNullable(activities.get(sessionId));
    }

    @Override
    public synchronized List<CodingSessionActivity> listActivities(
            TenantRef tenant, PrincipalRef principal, ProjectId projectId, CodingSessionQuery query) {
        Comparator<CodingSessionActivity> ordering = Comparator.comparing(CodingSessionActivity::lastActivityAt)
                .thenComparing(value -> value.sessionId().value())
                .reversed();
        return activities.values().stream()
                .filter(value -> value.tenant().equals(tenant)
                        && value.principal().equals(principal)
                        && value.projectId().equals(projectId))
                .filter(value -> query.text()
                        .map(text -> value.displayName()
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains(text.toLowerCase(java.util.Locale.ROOT)))
                        .orElse(true))
                .filter(value ->
                        query.after().map(cursor -> after(value, cursor)).orElse(true))
                .sorted(ordering)
                .limit((long) query.limit() + 1)
                .toList();
    }

    @Override
    public synchronized CodingSessionActivity reserveActive(
            AgentSessionId sessionId, long expectedRevision, String dispatchKey, Instant updatedAt) {
        CodingSessionActivity current = requireActivity(sessionId);
        if (current.activeDispatchKey().filter(dispatchKey::equals).isPresent()) return current;
        if (current.revision() != expectedRevision) throw conflict("coding session revision is stale");
        if (current.activeRunId().isPresent() || current.activeDispatchKey().isPresent()) {
            throw conflict("coding session already has an active Run or dispatch");
        }
        CodingSessionActivity updated = new CodingSessionActivity(
                current.sessionId(),
                current.projectId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.of(dispatchKey),
                current.createdAt(),
                updatedAt,
                current.revision() + 1);
        activities.put(sessionId, updated);
        return updated;
    }

    @Override
    public synchronized CodingSessionActivity activateRun(
            AgentSessionId sessionId, String dispatchKey, AgentRunId runId, long runVersion, Instant updatedAt) {
        CodingSessionActivity current = requireActivity(sessionId);
        if (current.activeRunId().filter(runId::equals).isPresent()) return current;
        if (current.activeDispatchKey().filter(dispatchKey::equals).isEmpty()) {
            throw conflict("active dispatch changed before Run activation");
        }
        CodingSessionActivity updated = new CodingSessionActivity(
                current.sessionId(),
                current.projectId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                Optional.of(runId),
                OptionalLong.of(runVersion),
                Optional.empty(),
                current.createdAt(),
                updatedAt,
                current.revision() + 1);
        activities.put(sessionId, updated);
        return updated;
    }

    @Override
    public synchronized CodingSessionActivity clearActive(
            AgentSessionId sessionId, AgentRunId runId, long expectedRevision, Instant updatedAt) {
        CodingSessionActivity current = requireActivity(sessionId);
        if (current.activeRunId().isEmpty()) return current;
        if (current.revision() != expectedRevision
                || current.activeRunId().filter(runId::equals).isEmpty()) {
            throw conflict("active Run changed before reconciliation");
        }
        CodingSessionActivity updated = new CodingSessionActivity(
                current.sessionId(),
                current.projectId(),
                current.tenant(),
                current.principal(),
                current.displayName(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                current.createdAt(),
                updatedAt,
                current.revision() + 1);
        activities.put(sessionId, updated);
        return updated;
    }

    @Override
    public synchronized CodingFollowUp enqueue(CodingFollowUp candidate) {
        CodingFollowUp existing = followUps.values().stream()
                .filter(value -> value.sessionId().equals(candidate.sessionId())
                        && value.idempotencyKeyDigest().equals(candidate.idempotencyKeyDigest()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(candidate.requestDigest())) {
                throw conflict("follow-up idempotency key is bound to another request");
            }
            return existing;
        }
        long sequence = followUps.values().stream()
                        .filter(value -> value.sessionId().equals(candidate.sessionId()))
                        .mapToLong(CodingFollowUp::sequence)
                        .max()
                        .orElse(0)
                + 1;
        CodingFollowUp stored = copyFollowUp(
                candidate,
                candidate.status(),
                sequence,
                candidate.dispatchedRunId(),
                candidate.updatedAt(),
                candidate.revision());
        followUps.put(stored.followUpId(), stored);
        return stored;
    }

    @Override
    public synchronized Optional<CodingFollowUp> findFollowUp(String followUpId) {
        return Optional.ofNullable(followUps.get(followUpId));
    }

    @Override
    public synchronized Optional<CodingFollowUp> findFollowUpByDispatchKey(String dispatchKey) {
        return followUps.values().stream()
                .filter(value -> value.dispatchKey().equals(dispatchKey))
                .findFirst();
    }

    @Override
    public synchronized Optional<CodingDispatchClaim> claimNextForDispatch(
            AgentSessionId sessionId, long expectedActivityRevision, Instant updatedAt) {
        CodingSessionActivity activity = requireActivity(sessionId);
        CodingFollowUp current = followUps.values().stream()
                .filter(value -> value.sessionId().equals(sessionId)
                        && (value.status() == CodingFollowUpStatus.PENDING
                                || value.status() == CodingFollowUpStatus.CLAIMED))
                .min(Comparator.comparingLong(CodingFollowUp::sequence))
                .orElse(null);
        if (current == null) return Optional.empty();
        if (current.status() == CodingFollowUpStatus.CLAIMED) {
            if (activity.activeDispatchKey()
                    .filter(current.dispatchKey()::equals)
                    .isEmpty()) {
                throw conflict("claimed follow-up has no matching active dispatch");
            }
            return Optional.of(new CodingDispatchClaim(activity, current));
        }
        if (activity.revision() != expectedActivityRevision
                || activity.activeRunId().isPresent()
                || activity.activeDispatchKey().isPresent()) {
            throw conflict("coding session changed before follow-up claim");
        }
        CodingFollowUp claimed = copyFollowUp(
                current,
                CodingFollowUpStatus.CLAIMED,
                current.sequence(),
                Optional.empty(),
                updatedAt,
                current.revision() + 1);
        CodingSessionActivity reserved = new CodingSessionActivity(
                activity.sessionId(),
                activity.projectId(),
                activity.tenant(),
                activity.principal(),
                activity.displayName(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.of(claimed.dispatchKey()),
                activity.createdAt(),
                updatedAt,
                activity.revision() + 1);
        followUps.put(claimed.followUpId(), claimed);
        activities.put(sessionId, reserved);
        return Optional.of(new CodingDispatchClaim(reserved, claimed));
    }

    @Override
    public synchronized CodingFollowUp markDispatched(
            String followUpId, long expectedRevision, AgentRunId runId, Instant updatedAt) {
        CodingFollowUp current = requireFollowUp(followUpId);
        if (current.status() == CodingFollowUpStatus.DISPATCHED
                && current.dispatchedRunId().filter(runId::equals).isPresent()) {
            return current;
        }
        if (current.status() != CodingFollowUpStatus.CLAIMED || current.revision() != expectedRevision) {
            throw conflict("follow-up changed before dispatch completion");
        }
        CodingFollowUp dispatched = copyFollowUp(
                current,
                CodingFollowUpStatus.DISPATCHED,
                current.sequence(),
                Optional.of(runId),
                updatedAt,
                current.revision() + 1);
        followUps.put(followUpId, dispatched);
        return dispatched;
    }

    @Override
    public synchronized CodingFollowUp restore(String followUpId, long expectedRevision, Instant updatedAt) {
        CodingFollowUp current = requireFollowUp(followUpId);
        if (current.status() == CodingFollowUpStatus.RESTORED) return current;
        if ((current.status() != CodingFollowUpStatus.PENDING && current.status() != CodingFollowUpStatus.CLAIMED)
                || current.revision() != expectedRevision) {
            throw conflict("follow-up cannot be restored from its current state");
        }
        CodingSessionActivity activity = requireActivity(current.sessionId());
        if (activity.activeDispatchKey().filter(current.dispatchKey()::equals).isPresent()) {
            throw conflict("follow-up is already reserved for dispatch");
        }
        CodingFollowUp restored = copyFollowUp(
                current,
                CodingFollowUpStatus.RESTORED,
                current.sequence(),
                Optional.empty(),
                updatedAt,
                current.revision() + 1);
        followUps.put(followUpId, restored);
        return restored;
    }

    @Override
    public synchronized int queuedCount(AgentSessionId sessionId) {
        return Math.toIntExact(followUps.values().stream()
                .filter(value -> value.sessionId().equals(sessionId)
                        && (value.status() == CodingFollowUpStatus.PENDING
                                || value.status() == CodingFollowUpStatus.CLAIMED))
                .count());
    }

    private CodingSessionActivity requireActivity(AgentSessionId sessionId) {
        CodingSessionActivity value = activities.get(sessionId);
        if (value == null) throw conflict("coding session activity is unavailable");
        return value;
    }

    private CodingFollowUp requireFollowUp(String followUpId) {
        CodingFollowUp value = followUps.get(followUpId);
        if (value == null) throw conflict("follow-up is unavailable");
        return value;
    }

    private static boolean after(CodingSessionActivity value, CodingSessionCursor cursor) {
        return value.lastActivityAt().isBefore(cursor.lastActivityAt())
                || (value.lastActivityAt().equals(cursor.lastActivityAt())
                        && value.sessionId()
                                        .value()
                                        .compareTo(cursor.sessionId().value())
                                < 0);
    }

    private static String commandKey(CodingCommandBinding value) {
        return value.callerScopeDigest() + "|" + value.operation() + "|" + value.idempotencyKeyDigest();
    }

    private static boolean sameRequest(CodingCommandBinding first, CodingCommandBinding second) {
        return first.requestDigest().equals(second.requestDigest())
                && first.projectId().equals(second.projectId());
    }

    private static CodingFollowUp copyFollowUp(
            CodingFollowUp value,
            CodingFollowUpStatus status,
            long sequence,
            Optional<AgentRunId> runId,
            Instant updatedAt,
            long revision) {
        return new CodingFollowUp(
                value.followUpId(),
                value.sessionId(),
                value.boundRunId(),
                value.message(),
                value.attachments(),
                value.idempotencyKeyDigest(),
                value.requestDigest(),
                value.dispatchKey(),
                status,
                sequence,
                runId,
                value.createdAt(),
                updatedAt,
                revision);
    }

    private static IllegalStateException conflict(String message) {
        return new IllegalStateException(message);
    }
}
