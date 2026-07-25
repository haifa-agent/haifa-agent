package io.haifa.agent.core.plan;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.persistence.DomainReconstitution;
import io.haifa.agent.core.persistence.DomainReconstitutionException;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Revisioned plan aggregate for one run. */
public final class AgentPlan {

    private final AgentPlanId id;
    private final AgentRunId runId;
    private final Instant createdAt;
    private String objective;
    private List<TodoItem> items;
    private long revision;
    private Instant updatedAt;

    public AgentPlan(AgentPlanId id, AgentRunId runId, String objective, List<TodoItem> items, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.objective = requireText(objective, "objective");
        this.items = uniqueItems(items);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
        this.revision = 1;
    }

    public static AgentPlan reconstitute(AgentPlanPersistenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        DomainReconstitution.requireSupportedVersion(snapshot.schemaVersion(), "AgentPlan");
        if (snapshot.revision() < 1) {
            DomainReconstitution.invalid("plan revision must be positive");
        }
        DomainReconstitution.requireChronological(snapshot.createdAt(), snapshot.updatedAt(), "plan");
        try {
            List<TodoItem> restoredItems =
                    snapshot.items().stream().map(TodoItem::reconstitute).toList();
            AgentPlan plan = new AgentPlan(
                    snapshot.id(), snapshot.runId(), snapshot.objective(), restoredItems, snapshot.createdAt());
            plan.revision = snapshot.revision();
            plan.updatedAt = snapshot.updatedAt();
            return plan;
        } catch (DomainReconstitutionException exception) {
            throw exception;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw DomainReconstitution.invalid("AgentPlan", exception);
        }
    }

    public AgentPlanPersistenceSnapshot persistenceSnapshot() {
        return new AgentPlanPersistenceSnapshot(
                DomainReconstitution.SCHEMA_VERSION,
                id,
                runId,
                createdAt,
                objective,
                items.stream().map(TodoItem::persistenceSnapshot).toList(),
                revision,
                updatedAt);
    }

    public void revise(String objective, List<TodoItem> items, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("plan update time must not move backwards");
        }
        this.objective = requireText(objective, "objective");
        this.items = uniqueItems(items);
        this.updatedAt = at;
        this.revision++;
    }

    private static List<TodoItem> uniqueItems(List<TodoItem> source) {
        List<TodoItem> copy = List.copyOf(Objects.requireNonNull(source, "items must not be null"));
        if (copy.stream().map(TodoItem::id).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("plan item ids must be unique");
        }
        return copy;
    }

    public AgentPlanId id() {
        return id;
    }

    public AgentRunId runId() {
        return runId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String objective() {
        return objective;
    }

    public List<TodoItem> items() {
        return items;
    }

    public long revision() {
        return revision;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
