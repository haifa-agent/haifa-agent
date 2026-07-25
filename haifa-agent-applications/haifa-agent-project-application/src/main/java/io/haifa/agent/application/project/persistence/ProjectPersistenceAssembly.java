package io.haifa.agent.application.project.persistence;

import io.haifa.agent.application.project.product.InMemoryProjectProductSessionStore;
import io.haifa.agent.application.project.product.ProjectProductSession;
import io.haifa.agent.application.project.product.ProjectProductSessionStore;
import io.haifa.agent.application.project.product.ProjectSessionProvisioner;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal;
import io.haifa.agent.store.jsonl.JsonlTranscriptProjector;
import io.haifa.agent.store.jsonl.JsonlTranscriptWriter;
import io.haifa.agent.store.jsonl.SafeTranscriptMapperRegistry;
import io.haifa.agent.store.jsonl.TranscriptRedactor;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import io.haifa.agent.store.sqlite.SqliteStoreFoundation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Product-layer assembly of Runtime persistence adapters and optional JSONL projection. */
public final class ProjectPersistenceAssembly implements AutoCloseable {
    private final ProjectPersistenceMode mode;
    private final RuntimePersistencePorts ports;
    private final ProjectProductSessionStore productSessions;
    private final String workerId;
    private final SqliteStoreFoundation sqlite;
    private final JsonlTranscriptProjector projector;
    private final AtomicBoolean closing = new AtomicBoolean();

    private ProjectPersistenceAssembly(
            ProjectPersistenceMode mode,
            RuntimePersistencePorts ports,
            ProjectProductSessionStore productSessions,
            String workerId,
            SqliteStoreFoundation sqlite,
            JsonlTranscriptProjector projector) {
        this.mode = mode;
        this.ports = ports;
        this.productSessions = productSessions;
        this.workerId = workerId;
        this.sqlite = sqlite;
        this.projector = projector;
    }

    public static ProjectPersistenceAssembly open(
            ProjectPersistenceConfiguration configuration,
            Clock clock,
            IdentifierGenerator identifiers,
            ModelContinuationProtector protector) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(identifiers, "identifiers must not be null");
        String workerId = "project-runtime-" + identifiers.nextValue();
        if (configuration.mode() == ProjectPersistenceMode.MEMORY) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            RuntimePersistencePorts ports = RuntimePersistencePorts.inMemory(
                    store, new InMemoryToolExecutionJournal(), new InMemoryInteractionPort());
            return new ProjectPersistenceAssembly(
                    configuration.mode(), ports, new InMemoryProjectProductSessionStore(), workerId, null, null);
        }
        if (protector == null || !protector.supportsPersistentStorage()) {
            throw new IllegalArgumentException("SQLite persistence requires a durable continuation protector");
        }
        Path database = configuration.databasePath().orElseThrow();
        SqliteStoreConfiguration sqliteConfiguration = new SqliteStoreConfiguration(
                database, configuration.busyTimeoutMillis(), configuration.maximumPayloadBytes());
        SqliteStoreFoundation foundation = null;
        try {
            foundation =
                    SqliteStoreFoundation.initialize(sqliteConfiguration, clock, ProjectApplicationMigrations.all());
            RuntimePersistencePorts ports = foundation.persistencePorts(protector);
            ProjectProductSessionStore productSessions =
                    new SqliteProjectProductSessionStore(foundation.connections(), ports.sessions());
            JsonlTranscriptProjector projector = null;
            if (configuration.mode() == ProjectPersistenceMode.SQLITE_WITH_JSONL) {
                Path root = requireTranscriptRoot(configuration.transcriptRoot().orElseThrow());
                projector = new JsonlTranscriptProjector(
                        ports.outbox(),
                        ports.unitOfWork(),
                        SafeTranscriptMapperRegistry.defaults(),
                        new TranscriptRedactor(),
                        new JsonlTranscriptWriter(root));
            }
            return new ProjectPersistenceAssembly(
                    configuration.mode(), ports, productSessions, workerId, foundation, projector);
        } catch (RuntimeException | Error exception) {
            if (foundation != null) {
                try {
                    foundation.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw exception;
        }
    }

    public ProjectPersistenceMode mode() {
        return mode;
    }

    public RuntimePersistencePorts ports() {
        return ports;
    }

    public ProjectProductSessionStore productSessions() {
        return productSessions;
    }

    public String workerId() {
        return workerId;
    }

    public RuntimeCoreBuilder configure(RuntimeCoreBuilder builder) {
        Objects.requireNonNull(builder, "builder must not be null");
        builder.persistence(ports).workerId(workerId);
        if (mode != ProjectPersistenceMode.MEMORY) builder.persistenceRetry(sqliteBusyRetry());
        return builder;
    }

    public void attachProjection(AgentRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        if (projector != null) runtime.addListener(ignored -> projectCommittedEvents());
    }

    public ProjectSessionProvisioner projectSessionProvisioner(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return session -> provisionProjectSession(session, clock);
    }

    public void provisionUserSession(AgentSessionId sessionId, TenantRef tenant, PrincipalRef principal, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        ports.unitOfWork().execute(() -> {
            if (ports.sessions().find(sessionId).isEmpty()) {
                ports.sessions()
                        .insert(AgentSession.open(
                                sessionId, tenant, principal, null, SessionScope.USER, clock.instant(), Map.of()));
            }
            return null;
        });
    }

    public int projectCommittedEvents() {
        if (projector == null || closing.get()) return 0;
        return projector.projectPending();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        if (projector != null) {
            try {
                projector.projectPending();
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (sqlite != null) {
            try {
                sqlite.close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void provisionProjectSession(ProjectProductSession session, Clock clock) {
        Objects.requireNonNull(session, "session must not be null");
        ports.unitOfWork().execute(() -> {
            Optional<AgentSession> existing = ports.sessions().find(session.sessionId());
            if (existing.isPresent()) {
                validateCoreSession(existing.orElseThrow(), session);
            } else {
                ports.sessions()
                        .insert(AgentSession.open(
                                session.sessionId(),
                                session.tenant(),
                                session.principal(),
                                new ProjectRef(session.projectId().value()),
                                SessionScope.PROJECT,
                                clock.instant(),
                                Map.of()));
            }
            return null;
        });
    }

    private static void validateCoreSession(AgentSession core, ProjectProductSession product) {
        if (!core.tenant().equals(product.tenant())
                || !core.owner().equals(product.principal())
                || core.project()
                        .map(ProjectRef::projectId)
                        .filter(product.projectId().value()::equals)
                        .isEmpty()) {
            throw new IllegalStateException("Core Session and Project Product Session are inconsistent");
        }
    }

    private static Path requireTranscriptRoot(Path root) {
        if (!root.isAbsolute()
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)
                || !Files.isWritable(root)) {
            throw new IllegalArgumentException("transcript root must be an existing writable controlled directory");
        }
        return root.normalize();
    }

    private static RetryPolicy sqliteBusyRetry() {
        return new RetryPolicy(
                3,
                error -> error instanceof SqliteStoreException store
                        && store.failure() == SqliteStoreFailure.DATABASE_BUSY,
                failedAttempt -> Duration.ofMillis(Math.min(200, 25L << Math.min(failedAttempt - 1, 3))));
    }
}
