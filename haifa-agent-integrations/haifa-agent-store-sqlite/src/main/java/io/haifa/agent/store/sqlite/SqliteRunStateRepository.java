package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunPersistenceSnapshot;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RunRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.AgentErrorPayload;
import io.haifa.agent.store.sqlite.payload.RunResultPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.PersistenceException;

/** SQLite/MyBatis implementation of the logical run aggregate repository. */
public final class SqliteRunStateRepository implements RunStateRepository {

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteRunStateRepository(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public void insert(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        execute(() -> {
            try {
                unitOfWork.mapper(RuntimeStoreMapper.class).insertRun(toRow(run));
                return null;
            } catch (PersistenceException exception) {
                throw new IllegalStateException(
                        "run already exists or violates its references: " + run.id(), exception);
            }
        });
    }

    @Override
    public void save(AgentRun run, long expectedVersion) {
        Objects.requireNonNull(run, "run must not be null");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        execute(() -> {
            int updated = unitOfWork.mapper(RuntimeStoreMapper.class).updateRun(toRow(run), expectedVersion);
            if (updated != 1) {
                throw new OptimisticLockException(
                        "run version conflict for " + run.id().value() + " at " + expectedVersion);
            }
            return null;
        });
    }

    @Override
    public Optional<AgentRun> find(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findRun(runId.value()))
                .map(this::fromRow));
    }

    private RunRow toRow(AgentRun run) {
        AgentRunPersistenceSnapshot value = run.persistenceSnapshot();
        EncodedPayload result = value.result() == null
                ? null
                : codecs.encode(SqliteRuntimePayloadTypes.RUN_RESULT, RunResultPayload.from(value.result()));
        EncodedPayload error = value.error() == null
                ? null
                : codecs.encode(SqliteRuntimePayloadTypes.AGENT_ERROR, AgentErrorPayload.from(value.error()));
        return new RunRow(
                value.id().value(),
                value.schemaVersion(),
                value.rootRunId().value(),
                value.parentRunId() == null ? null : value.parentRunId().value(),
                value.sessionId().value(),
                value.project() == null ? null : value.project().projectId(),
                value.tenant().tenantId(),
                value.principal().principalId(),
                value.principal().principalType(),
                value.agentDefinitionId().value(),
                value.agentDefinitionVersion().toString(),
                value.productProfileId(),
                value.productProfileVersion(),
                value.runType().value(),
                value.invocationMode(),
                value.depth(),
                value.objective(),
                value.budget().maxInputTokens(),
                value.budget().maxOutputTokens(),
                value.budget().maxCachedInputTokens(),
                value.budget().maxToolCalls(),
                value.budget().maxModelCalls(),
                value.budget().maxChildRuns(),
                encodeBudgetCurrency(value.budget()),
                value.budget().maxCostMinorUnits(),
                value.limits().maxIterations(),
                value.limits().maxDepth(),
                value.limits().maxParallelChildren(),
                value.limits().maxWallTimeMillis(),
                value.limits().maxIdleTimeMillis(),
                value.limits().maxToolCalls(),
                value.limits().maxModelCalls(),
                value.limits().maxChildRuns(),
                value.configurationSnapshot().snapshotId(),
                value.status(),
                value.usage().inputTokens(),
                value.usage().outputTokens(),
                value.usage().cachedInputTokens(),
                value.usage().modelCalls(),
                value.usage().toolCalls(),
                value.usage().childRuns(),
                value.usage().costMinorUnits(),
                value.usage().wallTimeMillis(),
                result == null ? null : result.schemaVersion(),
                result == null ? null : result.bytes(),
                result == null ? null : result.hash(),
                error == null ? null : error.schemaVersion(),
                error == null ? null : error.bytes(),
                error == null ? null : error.hash(),
                value.waitingFor() == null ? null : value.waitingFor().interactionRequestId(),
                value.waitingFor() == null ? null : value.waitingFor().interactionType(),
                value.terminationReason() == null
                        ? null
                        : value.terminationReason().code(),
                value.terminationReason() == null
                        ? null
                        : value.terminationReason().description(),
                value.accumulatedHumanWaitMillis(),
                value.humanWaitStartedAt(),
                value.createdAt(),
                value.queuedAt(),
                value.startedAt(),
                value.suspendedAt(),
                value.resumedAt(),
                value.completedAt(),
                value.updatedAt(),
                value.version());
    }

    private AgentRun fromRow(RunRow row) {
        AgentRunResult result = row.resultPayload() == null
                ? null
                : codecs.decode(
                                SqliteRuntimePayloadTypes.RUN_RESULT,
                                new EncodedPayload(
                                        SqliteRuntimePayloadTypes.RUN_RESULT.name(),
                                        row.resultSchemaVersion(),
                                        row.resultPayload(),
                                        row.resultHash()))
                        .toDomain();
        AgentError error = row.errorPayload() == null
                ? null
                : codecs.decode(
                                SqliteRuntimePayloadTypes.AGENT_ERROR,
                                new EncodedPayload(
                                        SqliteRuntimePayloadTypes.AGENT_ERROR.name(),
                                        row.errorSchemaVersion(),
                                        row.errorPayload(),
                                        row.errorHash()))
                        .toDomain();
        DecodedBudgetCurrency decodedCurrency = decodeBudgetCurrency(
                row.budgetMaxCostCurrency(),
                row.budgetMaxInputTokens(),
                row.budgetMaxOutputTokens(),
                row.budgetMaxCostMinorUnits());
        return AgentRun.reconstitute(new AgentRunPersistenceSnapshot(
                row.schemaVersion(),
                new AgentRunId(row.runId()),
                new AgentRunId(row.rootRunId()),
                row.parentRunId() == null ? null : new AgentRunId(row.parentRunId()),
                new AgentSessionId(row.sessionId()),
                row.projectId() == null ? null : new ProjectRef(row.projectId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                new AgentDefinitionId(row.agentDefinitionId()),
                definitionVersion(row.agentDefinitionVersion()),
                row.productProfileId(),
                row.productProfileVersion(),
                new AgentRunType(row.runType()),
                row.invocationMode(),
                row.depth(),
                row.objective(),
                new AgentRunBudget(
                        decodedCurrency.mode(),
                        row.budgetMaxInputTokens(),
                        row.budgetMaxOutputTokens(),
                        row.budgetMaxCachedInputTokens(),
                        row.budgetMaxToolCalls(),
                        row.budgetMaxModelCalls(),
                        row.budgetMaxChildRuns(),
                        decodedCurrency.currency(),
                        row.budgetMaxCostMinorUnits()),
                new AgentRunLimits(
                        row.limitMaxIterations(),
                        row.limitMaxDepth(),
                        row.limitMaxParallelChildren(),
                        row.limitMaxWallTimeMillis(),
                        row.limitMaxIdleTimeMillis(),
                        row.limitMaxModelCalls(),
                        row.limitMaxToolCalls(),
                        row.limitMaxChildRuns()),
                new RunConfigurationSnapshotRef(row.configurationRef(), configurationHash(row.configurationRef())),
                row.createdAt(),
                row.status(),
                new AgentRunUsage(
                        row.usageInputTokens(),
                        row.usageOutputTokens(),
                        row.usageCachedInputTokens(),
                        row.usageModelCalls(),
                        row.usageToolCalls(),
                        row.usageChildRuns(),
                        row.usageCostMinorUnits(),
                        row.usageWallTimeMillis()),
                result,
                error,
                row.waitingRequestId() == null
                        ? null
                        : new InteractionRequestRef(row.waitingRequestId(), row.waitingRequestType()),
                row.terminationReason() == null
                        ? null
                        : new RunTerminationReason(row.terminationReason(), row.terminationDescription()),
                row.accumulatedHumanWaitMillis(),
                row.humanWaitStartedAt(),
                row.queuedAt(),
                row.startedAt(),
                row.suspendedAt(),
                row.resumedAt(),
                row.completedAt(),
                row.updatedAt(),
                row.version()));
    }

    private String configurationHash(String reference) {
        return execute(() -> {
            String hash = unitOfWork.mapper(RuntimeStoreMapper.class).configurationHash(reference);
            if (hash == null) throw new IllegalStateException("run configuration snapshot is missing");
            return hash;
        });
    }

    private static AgentDefinitionVersion definitionVersion(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("invalid agent definition version");
        return new AgentDefinitionVersion(
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static String encodeBudgetCurrency(AgentRunBudget budget) {
        return budget.quotaMode().name() + "|" + budget.maxCostCurrency();
    }

    private static record DecodedBudgetCurrency(io.haifa.agent.core.run.QuotaMode mode, String currency) {}

    private static DecodedBudgetCurrency decodeBudgetCurrency(
            String stored, long maxInput, long maxOutput, long maxCost) {
        if (stored != null && stored.contains("|")) {
            int index = stored.indexOf('|');
            String modeStr = stored.substring(0, index);
            String currency = stored.substring(index + 1);
            try {
                return new DecodedBudgetCurrency(io.haifa.agent.core.run.QuotaMode.valueOf(modeStr), currency);
            } catch (IllegalArgumentException ignored) {
                // fall through to legacy inference
            }
        }
        io.haifa.agent.core.run.QuotaMode mode = (maxInput == 0 && maxOutput == 0 && maxCost == 0)
                ? io.haifa.agent.core.run.QuotaMode.DISABLED
                : io.haifa.agent.core.run.QuotaMode.HARD_STOP;
        return new DecodedBudgetCurrency(mode, stored == null ? "USD" : stored);
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}
