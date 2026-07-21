package io.haifa.agent.core.session;

import static io.haifa.agent.core.support.DomainValues.immutableMap;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Multi-run conversation boundary that is deliberately not bound to one Agent definition. */
public final class AgentSession {

    private final AgentSessionId id;
    private final TenantRef tenant;
    private final PrincipalRef owner;
    private final ProjectRef project;
    private final SessionScope scope;
    private final Instant createdAt;
    private AgentSessionStatus status;
    private Instant updatedAt;
    private Instant closedAt;
    private long version;
    private Map<String, Object> metadata;

    private AgentSession(
            AgentSessionId id,
            TenantRef tenant,
            PrincipalRef owner,
            ProjectRef project,
            SessionScope scope,
            Instant createdAt,
            Map<String, Object> metadata) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.project = project;
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (scope == SessionScope.PROJECT && project == null) {
            throw new IllegalArgumentException("project scope requires a project reference");
        }
        this.status = AgentSessionStatus.ACTIVE;
        this.updatedAt = createdAt;
        this.metadata = immutableMap(metadata, "metadata");
    }

    public static AgentSession open(
            AgentSessionId id,
            TenantRef tenant,
            PrincipalRef owner,
            ProjectRef project,
            SessionScope scope,
            Instant createdAt,
            Map<String, Object> metadata) {
        return new AgentSession(id, tenant, owner, project, scope, createdAt, metadata);
    }

    public void archive(Instant at) {
        requireStatus(AgentSessionStatus.ACTIVE);
        changeStatus(AgentSessionStatus.ARCHIVED, at, false);
    }

    public void close(Instant at) {
        if (status != AgentSessionStatus.ACTIVE && status != AgentSessionStatus.ARCHIVED) {
            throw new IllegalStateException("only active or archived sessions can be closed");
        }
        changeStatus(AgentSessionStatus.CLOSED, at, true);
    }

    public void delete(Instant at) {
        if (status == AgentSessionStatus.DELETED) {
            throw new IllegalStateException("session is already deleted");
        }
        changeStatus(AgentSessionStatus.DELETED, at, true);
    }

    public void replaceMetadata(Map<String, Object> metadata, Instant at) {
        if (status == AgentSessionStatus.DELETED) {
            throw new IllegalStateException("deleted session cannot be changed");
        }
        requireChronological(at);
        this.metadata = immutableMap(metadata, "metadata");
        this.updatedAt = at;
        this.version++;
    }

    private void changeStatus(AgentSessionStatus target, Instant at, boolean closes) {
        requireChronological(at);
        status = target;
        updatedAt = at;
        if (closes) {
            closedAt = at;
        }
        version++;
    }

    private void requireChronological(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("session change time must not move backwards");
        }
    }

    private void requireStatus(AgentSessionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected session status " + expected + " but was " + status);
        }
    }

    public AgentSessionId id() {
        return id;
    }

    public TenantRef tenant() {
        return tenant;
    }

    public PrincipalRef owner() {
        return owner;
    }

    public Optional<ProjectRef> project() {
        return Optional.ofNullable(project);
    }

    public SessionScope scope() {
        return scope;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public AgentSessionStatus status() {
        return status;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Optional<Instant> closedAt() {
        return Optional.ofNullable(closedAt);
    }

    public long version() {
        return version;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
