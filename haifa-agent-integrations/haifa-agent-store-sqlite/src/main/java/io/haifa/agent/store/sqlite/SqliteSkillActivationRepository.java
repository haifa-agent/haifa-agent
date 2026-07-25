package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.skill.SkillActivationRepository;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.SkillActivationRow;
import io.haifa.agent.store.sqlite.payload.SkillActivationPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteSkillActivationRepository implements SkillActivationRepository {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final Clock clock;

    public SqliteSkillActivationRepository(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs, Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.codecs = Objects.requireNonNull(codecs);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SkillActivation saveSkillActivation(
            AgentRunId runId, SkillActivation activation, long maximumInstructionBytes, long maximumEstimatedTokens) {
        if (maximumInstructionBytes < 1 || maximumEstimatedTokens < 1) {
            throw new IllegalArgumentException("invalid skill activation budget");
        }
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            SkillActivationRow existing = mapper.findSkillActivation(
                    runId.value(), activation.binding().alias().value());
            if (existing != null) {
                SkillActivation restored = decode(existing);
                if (!restored.binding().equals(activation.binding())) {
                    throw new IllegalStateException("skill alias is already activated with a different frozen binding");
                }
                return restored;
            }
            if (Math.addExact(mapper.skillInstructionBytes(runId.value()), activation.instructionBytes())
                            > maximumInstructionBytes
                    || Math.addExact(mapper.skillEstimatedTokens(runId.value()), activation.estimatedTokens())
                            > maximumEstimatedTokens) {
                throw new IllegalStateException("skill activation instruction budget exceeded");
            }
            EncodedPayload payload =
                    codecs.encode(SqliteRuntimePayloadTypes.SKILL_ACTIVATION, new SkillActivationPayload(activation));
            mapper.insertSkillActivation(new SkillActivationRow(
                    runId.value(),
                    activation.binding().alias().value(),
                    activation.binding().coordinate().externalForm(),
                    activation.binding().coordinate().contentDigest().value(),
                    activation.reason(),
                    activation.requestedBy(),
                    activation.instructionBytes(),
                    activation.estimatedTokens(),
                    payload.schemaVersion(),
                    payload.bytes(),
                    payload.hash(),
                    activation.activatedAt()));
            return activation;
        });
    }

    @Override
    public Optional<SkillActivation> skillActivation(AgentRunId runId, SkillAlias alias) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findSkillActivation(runId.value(), alias.value()))
                .map(this::decode));
    }

    @Override
    public List<SkillActivation> skillActivations(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).skillActivations(runId.value()).stream()
                .map(this::decode)
                .toList());
    }

    @Override
    public long addSkillResourceReadBytes(AgentRunId runId, long bytes, long maximum) {
        if (bytes < 0 || maximum < 1) throw new IllegalArgumentException("invalid skill resource budget");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            mapper.insertSkillResourceUsage(runId.value(), clock.instant());
            if (mapper.addSkillResourceBytes(runId.value(), bytes, maximum, clock.instant()) != 1) {
                throw new IllegalStateException("skill resource read budget exceeded");
            }
            return Optional.ofNullable(mapper.skillResourceBytes(runId.value())).orElseThrow();
        });
    }

    private SkillActivation decode(SkillActivationRow row) {
        SkillActivation activation = codecs.decode(
                        SqliteRuntimePayloadTypes.SKILL_ACTIVATION,
                        new EncodedPayload(
                                SqliteRuntimePayloadTypes.SKILL_ACTIVATION.name(),
                                row.activationSchemaVersion(),
                                row.activationPayload(),
                                row.activationHash()))
                .activation();
        if (!row.skillAlias().equals(activation.binding().alias().value())
                || !row.coordinate().equals(activation.binding().coordinate().externalForm())
                || !row.contentDigest()
                        .equals(activation
                                .binding()
                                .coordinate()
                                .contentDigest()
                                .value())) {
            throw new IllegalStateException("skill activation columns do not match protected payload");
        }
        return activation;
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
