package io.haifa.agent.store.sqlite.mybatis;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.apache.ibatis.type.JdbcType;

public final class SqliteMyBatisSessionFactory {
    private static final String MIGRATION_MAPPER_RESOURCE =
            "/io/haifa/agent/store/sqlite/mybatis/MigrationMetadataMapper.xml";
    private static final String RUNTIME_STORE_MAPPER_RESOURCE =
            "/io/haifa/agent/store/sqlite/mybatis/RuntimeStoreMapper.xml";
    private static final String POLICY_STORE_MAPPER_RESOURCE =
            "/io/haifa/agent/store/sqlite/mybatis/PolicyStoreMapper.xml";
    private static final String SDK_CONVERSATION_MAPPER_RESOURCE =
            "/io/haifa/agent/store/sqlite/mybatis/SdkConversationMapper.xml";

    private final SqlSessionFactory sessions;

    public SqliteMyBatisSessionFactory(int maximumPayloadBytes) {
        this(maximumPayloadBytes, defaultMappers());
    }

    public static SqliteMyBatisSessionFactory withAdditionalMappers(
            int maximumPayloadBytes, List<MapperXml> additionalMappers) {
        var mappers = new ArrayList<>(defaultMappers());
        mappers.addAll(List.copyOf(Objects.requireNonNull(additionalMappers, "additionalMappers must not be null")));
        return new SqliteMyBatisSessionFactory(maximumPayloadBytes, mappers);
    }

    public SqliteMyBatisSessionFactory(int maximumPayloadBytes, List<MapperXml> mapperResources) {
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
        Configuration configuration = baseConfiguration(maximumPayloadBytes);
        for (MapperXml mapperResource : List.copyOf(mapperResources)) {
            parseMapper(configuration, Objects.requireNonNull(mapperResource, "mapperResource must not be null"));
        }
        configuration.getMappedStatementNames();
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    public SqlSession openSession(Connection connection) {
        return sessions.openSession(
                ExecutorType.SIMPLE, Objects.requireNonNull(connection, "connection must not be null"));
    }

    Configuration configuration() {
        return sessions.getConfiguration();
    }

    private static Configuration baseConfiguration(int maximumPayloadBytes) {
        ManagedTransactionFactory transactions = new ManagedTransactionFactory();
        Properties properties = new Properties();
        properties.setProperty("closeConnection", "false");
        transactions.setProperties(properties);
        Environment environment = new Environment("sqlite-runtime-store", transactions, DeniedDataSource.INSTANCE);
        Configuration configuration = new Configuration(environment);
        configuration.setCacheEnabled(false);
        configuration.setLazyLoadingEnabled(false);
        configuration.setAggressiveLazyLoading(false);
        configuration.setDefaultExecutorType(ExecutorType.SIMPLE);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setAutoMappingBehavior(AutoMappingBehavior.NONE);
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.FAILING);
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.setUseGeneratedKeys(false);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.setDefaultStatementTimeout(30);
        configuration.setDefaultFetchSize(100);
        configuration
                .getTypeHandlerRegistry()
                .register(Instant.class, JdbcType.BIGINT, InstantEpochMillisTypeHandler.class);
        configuration
                .getTypeHandlerRegistry()
                .register(byte[].class, JdbcType.BLOB, new BoundedBlobTypeHandler(maximumPayloadBytes));
        return configuration;
    }

    private static void parseMapper(Configuration configuration, MapperXml mapperResource) {
        if (mapperResource.xml().contains("${")) {
            throw mapperFailure("MyBatis mapper uses prohibited string substitution", null);
        }
        try {
            XMLMapperBuilder parser = new XMLMapperBuilder(
                    new StringReader(mapperResource.xml()),
                    configuration,
                    mapperResource.resourceName(),
                    configuration.getSqlFragments());
            parser.parse();
        } catch (RuntimeException exception) {
            throw mapperFailure("Unable to parse MyBatis mapper " + mapperResource.resourceName(), exception);
        }
    }

    private static MapperXml loadMapper(String resourceName) {
        try (InputStream input = SqliteMyBatisSessionFactory.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw mapperFailure("Bundled MyBatis mapper is missing", null);
            }
            return new MapperXml(resourceName, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw mapperFailure("Unable to read bundled MyBatis mapper", exception);
        }
    }

    private static List<MapperXml> defaultMappers() {
        return List.of(
                loadMapper(MIGRATION_MAPPER_RESOURCE),
                loadMapper(RUNTIME_STORE_MAPPER_RESOURCE),
                loadMapper(POLICY_STORE_MAPPER_RESOURCE),
                loadMapper(SDK_CONVERSATION_MAPPER_RESOURCE));
    }

    private static SqliteStoreException mapperFailure(String message, Throwable cause) {
        return cause == null
                ? new SqliteStoreException(SqliteStoreFailure.MAPPER_CONFIGURATION_FAILED, message)
                : new SqliteStoreException(SqliteStoreFailure.MAPPER_CONFIGURATION_FAILED, message, cause);
    }

    private enum DeniedDataSource implements DataSource {
        INSTANCE;

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("Mappers must use the active SQLite unit-of-work connection");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap is not supported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
