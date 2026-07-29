package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigration;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import io.haifa.agent.store.sqlite.mybatis.MapperXml;
import io.haifa.agent.store.sqlite.mybatis.SqliteMyBatisSessionFactory;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** SQLite store assembly for the complete runtime persistence-port adapter set. */
public final class SqliteStoreFoundation implements AutoCloseable {
    private final SqliteConnectionFactory connections;
    private final SqliteMyBatisSessionFactory myBatis;
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry.Builder codecs;
    private final VersionedPayloadCodecRegistry runtimeCodecs;
    private final Clock clock;
    private final int maximumPayloadBytes;

    private SqliteStoreFoundation(
            SqliteConnectionFactory connections,
            SqliteMyBatisSessionFactory myBatis,
            SqliteRuntimeUnitOfWork unitOfWork,
            VersionedPayloadCodecRegistry.Builder codecs,
            VersionedPayloadCodecRegistry runtimeCodecs,
            Clock clock,
            int maximumPayloadBytes) {
        this.connections = connections;
        this.myBatis = myBatis;
        this.unitOfWork = unitOfWork;
        this.codecs = codecs;
        this.runtimeCodecs = runtimeCodecs;
        this.clock = clock;
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    public static SqliteStoreFoundation initialize(SqliteStoreConfiguration configuration, Clock clock) {
        return initialize(configuration, clock, RuntimeStoreMigrations.all());
    }

    /**
     * Initializes the Runtime adapter with an application-owned migration set.
     *
     * <p>The supplied list must include the Runtime migrations unchanged. This overload lets an
     * application validate its complete schema history in one pass, including migrations that
     * belong above Runtime.
     */
    public static SqliteStoreFoundation initialize(
            SqliteStoreConfiguration configuration, Clock clock, List<SqliteMigration> migrations) {
        return initialize(configuration, clock, migrations, List.of());
    }

    public static SqliteStoreFoundation initialize(
            SqliteStoreConfiguration configuration,
            Clock clock,
            List<SqliteMigration> migrations,
            List<MapperXml> additionalMappers) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(migrations, "migrations must not be null");
        Objects.requireNonNull(additionalMappers, "additionalMappers must not be null");
        SqliteConnectionFactory connections = new SqliteConnectionFactory(configuration);
        connections.initialize();
        new SqliteMigrationRunner(connections, clock).migrate(migrations);
        SqliteMyBatisSessionFactory myBatis = SqliteMyBatisSessionFactory.withAdditionalMappers(
                configuration.maximumPayloadBytes(), additionalMappers);
        SqliteRuntimeUnitOfWork unitOfWork = new SqliteRuntimeUnitOfWork(connections, myBatis);
        return new SqliteStoreFoundation(
                connections,
                myBatis,
                unitOfWork,
                VersionedPayloadCodecRegistry.builder(configuration.maximumPayloadBytes()),
                SqliteRuntimePayloadTypes.create(configuration.maximumPayloadBytes()),
                clock,
                configuration.maximumPayloadBytes());
    }

    public SqliteConnectionFactory connections() {
        return connections;
    }

    public SqliteMyBatisSessionFactory myBatis() {
        return myBatis;
    }

    public SqliteRuntimeUnitOfWork unitOfWork() {
        return unitOfWork;
    }

    public VersionedPayloadCodecRegistry.Builder codecs() {
        return codecs;
    }

    public SqliteAgentSessionRepository agentSessions() {
        return new SqliteAgentSessionRepository(unitOfWork, runtimeCodecs);
    }

    public SqliteRunStateRepository runs() {
        return new SqliteRunStateRepository(unitOfWork, runtimeCodecs);
    }

    public SqliteExecutionAttemptRepository attempts() {
        return new SqliteExecutionAttemptRepository(unitOfWork, runtimeCodecs);
    }

    public SqliteSessionMessageRepository messages() {
        return new SqliteSessionMessageRepository(unitOfWork, runtimeCodecs);
    }

    public SqliteCheckpointRepository checkpoints() {
        return new SqliteCheckpointRepository(unitOfWork, runtimeCodecs);
    }

    public SqliteRuntimeEventAppender events() {
        return new SqliteRuntimeEventAppender(unitOfWork, runtimeCodecs);
    }

    public SqliteRuntimeOutboxPublisher outbox() {
        return new SqliteRuntimeOutboxPublisher(unitOfWork, runtimeCodecs, clock);
    }

    public SqliteIdempotencyRepository idempotency() {
        return new SqliteIdempotencyRepository(unitOfWork, runtimeCodecs, clock);
    }

    public SqliteToolExecutionJournal toolJournal() {
        return new SqliteToolExecutionJournal(unitOfWork, runtimeCodecs, clock);
    }

    public SqliteInteractionPort interactions() {
        return new SqliteInteractionPort(unitOfWork, runtimeCodecs, clock, maximumPayloadBytes);
    }

    public SqliteRunInputPort runInputs() {
        return new SqliteRunInputPort(unitOfWork, runtimeCodecs);
    }

    public SqlitePolicySnapshotStore policySnapshots() {
        return new SqlitePolicySnapshotStore(unitOfWork, runtimeCodecs);
    }

    public SqlitePolicyDecisionStore policyDecisions() {
        return new SqlitePolicyDecisionStore(unitOfWork, runtimeCodecs);
    }

    public SqlitePolicyAuthorizationEvidenceStore policyAuthorizationEvidence() {
        return new SqlitePolicyAuthorizationEvidenceStore(unitOfWork);
    }

    public SqliteApprovalGrantStore approvalGrants() {
        return new SqliteApprovalGrantStore(unitOfWork);
    }

    public SqliteProjectTrustStore projectTrusts() {
        return new SqliteProjectTrustStore(unitOfWork);
    }

    public SqliteConversationSummaryRepository summaries() {
        return new SqliteConversationSummaryRepository(unitOfWork, runtimeCodecs);
    }

    public SqliteRuntimeMemorySelectionRepository memorySelections() {
        return new SqliteRuntimeMemorySelectionRepository(unitOfWork, runtimeCodecs, clock);
    }

    public SqliteSkillActivationRepository skillActivations() {
        return new SqliteSkillActivationRepository(unitOfWork, runtimeCodecs, clock);
    }

    public SqliteToolResultAssetStore toolResultAssets() {
        return new SqliteToolResultAssetStore(unitOfWork, runtimeCodecs, clock);
    }

    public SqliteArtifactStore artifacts() {
        return new SqliteArtifactStore(unitOfWork);
    }

    public SqliteArtifactPayloadStore artifactPayloads() {
        return new SqliteArtifactPayloadStore(unitOfWork, maximumPayloadBytes, clock);
    }

    public SqliteRuntimeStateRepository runtimeState(ModelContinuationProtector protector) {
        SqliteSessionMessageRepository messages = messages();
        SqliteModelContinuationRepository continuations =
                new SqliteModelContinuationRepository(unitOfWork, runtimeCodecs, messages, protector);
        return new SqliteRuntimeStateRepository(
                messages,
                new SqliteLoopStateComponent(unitOfWork, runtimeCodecs, messages, clock),
                memorySelections(),
                continuations,
                skillActivations());
    }

    public RuntimePersistencePorts persistencePorts(ModelContinuationProtector protector) {
        SqliteSessionMessageRepository messages = messages();
        SqliteRuntimeStateRepository state = new SqliteRuntimeStateRepository(
                messages,
                new SqliteLoopStateComponent(unitOfWork, runtimeCodecs, messages, clock),
                memorySelections(),
                new SqliteModelContinuationRepository(unitOfWork, runtimeCodecs, messages, protector),
                skillActivations());
        return new RuntimePersistencePorts(
                agentSessions(),
                runs(),
                attempts(),
                checkpoints(),
                state,
                events(),
                outbox(),
                idempotency(),
                unitOfWork,
                toolJournal(),
                interactions(),
                runInputs(),
                summaries(),
                toolResultAssets(),
                messages);
    }

    @Override
    public void close() {
        connections.close();
    }
}
