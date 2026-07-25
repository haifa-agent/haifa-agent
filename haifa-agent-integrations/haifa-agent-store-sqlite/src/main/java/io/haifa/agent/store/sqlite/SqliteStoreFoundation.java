package io.haifa.agent.store.sqlite;

import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import io.haifa.agent.store.sqlite.mybatis.SqliteMyBatisSessionFactory;
import java.time.Clock;
import java.util.Objects;

/**
 * Task 03 infrastructure assembly. Business repositories are intentionally added only by the
 * subsequent persistence-adapter task.
 */
public final class SqliteStoreFoundation {
    private final SqliteConnectionFactory connections;
    private final SqliteMyBatisSessionFactory myBatis;
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry.Builder codecs;

    private SqliteStoreFoundation(
            SqliteConnectionFactory connections,
            SqliteMyBatisSessionFactory myBatis,
            SqliteRuntimeUnitOfWork unitOfWork,
            VersionedPayloadCodecRegistry.Builder codecs) {
        this.connections = connections;
        this.myBatis = myBatis;
        this.unitOfWork = unitOfWork;
        this.codecs = codecs;
    }

    public static SqliteStoreFoundation initialize(SqliteStoreConfiguration configuration, Clock clock) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        SqliteConnectionFactory connections = new SqliteConnectionFactory(configuration);
        connections.initialize();
        new SqliteMigrationRunner(connections, clock).migrate(RuntimeStoreMigrations.all());
        SqliteMyBatisSessionFactory myBatis = new SqliteMyBatisSessionFactory(configuration.maximumPayloadBytes());
        SqliteRuntimeUnitOfWork unitOfWork = new SqliteRuntimeUnitOfWork(connections, myBatis);
        return new SqliteStoreFoundation(
                connections,
                myBatis,
                unitOfWork,
                VersionedPayloadCodecRegistry.builder(configuration.maximumPayloadBytes()));
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
}
