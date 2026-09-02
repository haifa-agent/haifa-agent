package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.AgentPlanPersistenceSnapshot;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepPersistenceSnapshot;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallPersistenceSnapshot;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.ConfigurationRow;
import io.haifa.agent.store.sqlite.mybatis.PlanRow;
import io.haifa.agent.store.sqlite.mybatis.RunOutputRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.StepRow;
import io.haifa.agent.store.sqlite.mybatis.ToolCallRow;
import io.haifa.agent.store.sqlite.payload.AgentErrorPayload;
import io.haifa.agent.store.sqlite.payload.PlanItemsPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import io.haifa.agent.store.sqlite.payload.StepResultPayload;
import io.haifa.agent.store.sqlite.payload.StringPayload;
import io.haifa.agent.store.sqlite.payload.ToolArgumentsPayload;
import io.haifa.agent.store.sqlite.payload.ToolResultPayload;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** SQLite components for messages, steps, tool calls, plans, outputs and frozen configuration. */
final class SqliteLoopStateComponent {

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final SqliteSessionMessageRepository messages;
    private final Clock clock;

    SqliteLoopStateComponent(
            SqliteRuntimeUnitOfWork unitOfWork,
            VersionedPayloadCodecRegistry codecs,
            SqliteSessionMessageRepository messages,
            Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    void appendStep(AgentStep step) {
        execute(() -> {
            unitOfWork.mapper(RuntimeStoreMapper.class).insertStep(toRow(step));
            return null;
        });
    }

    void appendToolCall(ToolCall call) {
        execute(() -> {
            unitOfWork.mapper(RuntimeStoreMapper.class).insertToolCall(toRow(call));
            return null;
        });
    }

    void savePlan(AgentPlan plan) {
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            PlanRow row = toRow(plan);
            if (mapper.findPlan(plan.runId().value()) == null) mapper.insertPlan(row);
            else if (mapper.updatePlan(row) != 1) throw new IllegalStateException("plan update was not applied");
            return null;
        });
    }

    List<AgentStep> steps(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).stepsForRun(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    List<ToolCall> toolCalls(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).toolCallsForRun(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    Optional<AgentPlan> plan(AgentRunId runId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findPlan(runId.value()))
                .map(this::fromRow));
    }

    void saveOutput(AgentRunId runId, String output) {
        Objects.requireNonNull(output, "output must not be null");
        execute(() -> {
            EncodedPayload encoded = codecs.encode(SqliteRuntimePayloadTypes.STRING, new StringPayload(output));
            int inserted = unitOfWork
                    .mapper(RuntimeStoreMapper.class)
                    .insertOutput(new RunOutputRow(
                            runId.value(),
                            encoded.schemaVersion(),
                            encoded.bytes(),
                            encoded.hash(),
                            java.time.Instant.ofEpochMilli(clock.millis())));
            if (inserted != 1) throw new IllegalStateException("run output is already stored");
            return null;
        });
    }

    AgentMessage saveFinalOutputAndMessage(AgentRunId runId, String output, SessionMessageDraft message) {
        if (!message.runId().equals(Optional.of(runId))) {
            throw new IllegalArgumentException("final message must belong to the output run");
        }
        return execute(() -> {
            saveOutput(runId, output);
            return messages.appendSessionMessage(message);
        });
    }

    Optional<String> output(AgentRunId runId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findOutput(runId.value()))
                .map(row -> codecs.decode(
                                SqliteRuntimePayloadTypes.STRING,
                                new EncodedPayload(
                                        SqliteRuntimePayloadTypes.STRING.name(),
                                        row.outputSchemaVersion(),
                                        row.outputPayload(),
                                        row.outputHash()))
                        .value()));
    }

    void saveConfiguration(RuntimeConfigurationSnapshot configuration) {
        execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            ConfigurationRow current =
                    mapper.findConfiguration(configuration.reference().snapshotId());
            if (current != null) {
                if (!current.contentHash().equals(configuration.reference().contentHash())) {
                    throw new IllegalStateException("configuration snapshot id collision");
                }
                return null;
            }
            EncodedPayload content = codecs.encode(SqliteRuntimePayloadTypes.CONFIGURATION, configuration);
            ConfigurationRow row = new ConfigurationRow(
                    configuration.reference().snapshotId(),
                    "1",
                    configuration.definitionId().value(),
                    configuration.definitionVersion().toString(),
                    configuration.profileId(),
                    configuration.profileVersion(),
                    configuration.runType().value(),
                    content.schemaVersion(),
                    content.bytes(),
                    configuration.reference().contentHash(),
                    content.hash(),
                    java.time.Instant.ofEpochMilli(clock.millis()));
            mapper.insertConfiguration(row);
            ConfigurationRow stored =
                    mapper.findConfiguration(configuration.reference().snapshotId());
            if (stored == null
                    || !stored.contentHash().equals(configuration.reference().contentHash())
                    || !stored.contentPayloadHash().equals(content.hash())) {
                throw new IllegalStateException("configuration snapshot collision");
            }
            return null;
        });
    }

    Optional<RuntimeConfigurationSnapshot> configuration(String reference, String contentHash) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findConfiguration(reference))
                .filter(row -> row.contentHash().equals(contentHash))
                .map(this::configuration));
    }

    private RuntimeConfigurationSnapshot configuration(ConfigurationRow row) {
        return codecs.decode(
                SqliteRuntimePayloadTypes.CONFIGURATION,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.CONFIGURATION.name(),
                        row.contentSchemaVersion(),
                        row.contentPayload(),
                        row.contentPayloadHash()));
    }

    private StepRow toRow(AgentStep step) {
        AgentStepPersistenceSnapshot value = step.persistenceSnapshot();
        EncodedPayload result = value.result() == null
                ? null
                : codecs.encode(SqliteRuntimePayloadTypes.STEP_RESULT, StepResultPayload.from(value.result()));
        EncodedPayload error = value.error() == null
                ? null
                : codecs.encode(
                        SqliteRuntimePayloadTypes.AGENT_ERROR,
                        AgentErrorPayload.from(value.error().error()));
        return new StepRow(
                value.id().value(),
                value.schemaVersion(),
                value.runId().value(),
                value.parentStepId() == null ? null : value.parentStepId().value(),
                value.branchId(),
                value.type().value(),
                value.sequence(),
                value.status(),
                payloadVersion(result),
                payloadBytes(result),
                payloadHash(result),
                payloadVersion(error),
                payloadBytes(error),
                payloadHash(error),
                value.createdAt(),
                value.startedAt(),
                value.completedAt(),
                value.version());
    }

    private AgentStep fromRow(StepRow row) {
        var result = row.resultPayload() == null
                ? null
                : codecs.decode(
                                SqliteRuntimePayloadTypes.STEP_RESULT,
                                encoded(
                                        SqliteRuntimePayloadTypes.STEP_RESULT.name(),
                                        row.resultSchemaVersion(),
                                        row.resultPayload(),
                                        row.resultHash()))
                        .toDomain();
        AgentError error = decodeError(row.errorSchemaVersion(), row.errorPayload(), row.errorHash());
        return AgentStep.reconstitute(new AgentStepPersistenceSnapshot(
                row.schemaVersion(),
                new AgentStepId(row.stepId()),
                new AgentRunId(row.runId()),
                row.parentStepId() == null ? null : new AgentStepId(row.parentStepId()),
                row.branchId(),
                new AgentStepType(row.type()),
                row.sequence(),
                row.createdAt(),
                row.status(),
                row.startedAt(),
                row.completedAt(),
                result,
                error == null ? null : new AgentStepError(error),
                row.version()));
    }

    private ToolCallRow toRow(ToolCall call) {
        ToolCallPersistenceSnapshot value = call.persistenceSnapshot();
        EncodedPayload arguments =
                codecs.encode(SqliteRuntimePayloadTypes.TOOL_ARGUMENTS, ToolArgumentsPayload.from(value.arguments()));
        EncodedPayload result = value.result() == null
                ? null
                : codecs.encode(SqliteRuntimePayloadTypes.TOOL_RESULT, ToolResultPayload.from(value.result()));
        EncodedPayload error = value.error() == null
                ? null
                : codecs.encode(
                        SqliteRuntimePayloadTypes.AGENT_ERROR,
                        AgentErrorPayload.from(value.error().error()));
        return new ToolCallRow(
                value.id().value(),
                value.schemaVersion(),
                value.runId().value(),
                value.stepId().value(),
                value.providerCorrelationId() == null
                        ? null
                        : value.providerCorrelationId().value(),
                value.idempotencyKey() == null ? null : value.idempotencyKey().value(),
                value.toolName(),
                value.toolVersion(),
                arguments.schemaVersion(),
                arguments.bytes(),
                arguments.hash(),
                value.status(),
                payloadVersion(result),
                payloadBytes(result),
                payloadHash(result),
                payloadVersion(error),
                payloadBytes(error),
                payloadHash(error),
                value.requestedAt(),
                value.startedAt(),
                value.completedAt(),
                value.version());
    }

    private ToolCall fromRow(ToolCallRow row) {
        var arguments = codecs.decode(
                        SqliteRuntimePayloadTypes.TOOL_ARGUMENTS,
                        encoded(
                                SqliteRuntimePayloadTypes.TOOL_ARGUMENTS.name(),
                                row.argumentsSchemaVersion(),
                                row.argumentsPayload(),
                                row.argumentsHash()))
                .toDomain();
        var result = row.resultPayload() == null
                ? null
                : codecs.decode(
                                SqliteRuntimePayloadTypes.TOOL_RESULT,
                                encoded(
                                        SqliteRuntimePayloadTypes.TOOL_RESULT.name(),
                                        row.resultSchemaVersion(),
                                        row.resultPayload(),
                                        row.resultHash()))
                        .toDomain();
        AgentError error = decodeError(row.errorSchemaVersion(), row.errorPayload(), row.errorHash());
        return ToolCall.reconstitute(new ToolCallPersistenceSnapshot(
                row.schemaVersion(),
                new ToolCallId(row.toolCallId()),
                new AgentRunId(row.runId()),
                new AgentStepId(row.stepId()),
                row.providerCorrelationId() == null
                        ? null
                        : new ProviderToolCallCorrelationId(row.providerCorrelationId()),
                row.idempotencyKey() == null ? null : new RuntimeIdempotencyKey(row.idempotencyKey()),
                row.toolName(),
                row.toolVersion(),
                arguments,
                row.requestedAt(),
                row.status(),
                row.startedAt(),
                row.completedAt(),
                result,
                error == null ? null : new ToolExecutionError(error),
                row.version()));
    }

    private PlanRow toRow(AgentPlan plan) {
        AgentPlanPersistenceSnapshot value = plan.persistenceSnapshot();
        EncodedPayload items =
                codecs.encode(SqliteRuntimePayloadTypes.PLAN_ITEMS, PlanItemsPayload.from(value.items()));
        return new PlanRow(
                value.id().value(),
                value.schemaVersion(),
                value.runId().value(),
                value.objective(),
                items.schemaVersion(),
                items.bytes(),
                items.hash(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private AgentPlan fromRow(PlanRow row) {
        PlanItemsPayload items = codecs.decode(
                SqliteRuntimePayloadTypes.PLAN_ITEMS,
                encoded(
                        SqliteRuntimePayloadTypes.PLAN_ITEMS.name(),
                        row.itemsSchemaVersion(),
                        row.itemsPayload(),
                        row.itemsHash()));
        return AgentPlan.reconstitute(new AgentPlanPersistenceSnapshot(
                row.schemaVersion(),
                new AgentPlanId(row.planId()),
                new AgentRunId(row.runId()),
                row.createdAt(),
                row.objective(),
                items.toSnapshots(),
                row.revision(),
                row.updatedAt()));
    }

    private AgentError decodeError(String version, byte[] bytes, String hash) {
        return bytes == null
                ? null
                : codecs.decode(
                                SqliteRuntimePayloadTypes.AGENT_ERROR,
                                encoded(SqliteRuntimePayloadTypes.AGENT_ERROR.name(), version, bytes, hash))
                        .toDomain();
    }

    private static EncodedPayload encoded(String type, String version, byte[] bytes, String hash) {
        return new EncodedPayload(type, version, bytes, hash);
    }

    private static String payloadVersion(EncodedPayload payload) {
        return payload == null ? null : payload.schemaVersion();
    }

    private static byte[] payloadBytes(EncodedPayload payload) {
        return payload == null ? null : payload.bytes();
    }

    private static String payloadHash(EncodedPayload payload) {
        return payload == null ? null : payload.hash();
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
