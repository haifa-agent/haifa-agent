package io.haifa.agent.store.sqlite;

import io.haifa.agent.orchestration.core.spi.WorkflowUnitOfWork;
import io.haifa.agent.store.sqlite.orchestration.migration.WorkflowStoreMigrations;
import java.time.Clock;
import java.util.Objects;

/** Explicit, opt-in SQLite assembly for durable Workflow persistence. */
public final class SqliteWorkflowStoreFoundation implements AutoCloseable {
    private final SqliteStoreFoundation runtime;
    private final SqliteWorkflowStore workflows;
    private final WorkflowUnitOfWork unitOfWork;

    private SqliteWorkflowStoreFoundation(SqliteStoreFoundation runtime, int maximumPayloadBytes) {
        this.runtime = runtime;
        this.workflows = new SqliteWorkflowStore(runtime.unitOfWork(), maximumPayloadBytes);
        this.unitOfWork = new WorkflowUnitOfWork() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return runtime.unitOfWork().execute(work);
            }
        };
    }

    public static SqliteWorkflowStoreFoundation initialize(SqliteStoreConfiguration configuration, Clock clock) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        SqliteStoreFoundation runtime =
                SqliteStoreFoundation.initialize(configuration, clock, WorkflowStoreMigrations.complete());
        return new SqliteWorkflowStoreFoundation(runtime, configuration.maximumPayloadBytes());
    }

    public SqliteStoreFoundation runtime() {
        return runtime;
    }

    public SqliteWorkflowStore workflows() {
        return workflows;
    }

    public WorkflowUnitOfWork unitOfWork() {
        return unitOfWork;
    }

    @Override
    public void close() {
        runtime.close();
    }
}
