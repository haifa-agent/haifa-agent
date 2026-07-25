package io.haifa.agent.store.sqlite.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteTestSupport;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMyBatisSessionFactoryTest {
    @TempDir
    Path directory;

    @Test
    void validatesBundledMapperAtStartupAndUsesExplicitResultMap() {
        SqliteMyBatisSessionFactory factory = new SqliteMyBatisSessionFactory(1_024);

        assertThat(factory.configuration().hasMapper(MigrationMetadataMapper.class))
                .isTrue();
        assertThat(factory.configuration().isCacheEnabled()).isFalse();
        assertThat(factory.configuration().isLazyLoadingEnabled()).isFalse();
        assertThat(factory.configuration().getLocalCacheScope().name()).isEqualTo("STATEMENT");
        assertThat(factory.configuration().getDefaultExecutorType().name()).isEqualTo("SIMPLE");
        assertThat(factory.configuration().getCacheNames()).isEmpty();
    }

    @Test
    void rejectsStringSubstitutionAndUnknownTypeHandlerAtStartup() {
        MapperXml substitution = new MapperXml(
                "substitution.xml", mapperXml("SELECT version FROM schema_migration WHERE name = '${name}'"));
        assertThatThrownBy(() -> new SqliteMyBatisSessionFactory(1_024, List.of(substitution)))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("prohibited");

        MapperXml unknownHandler = new MapperXml(
                "unknown-handler.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="io.haifa.agent.store.sqlite.mybatis.MigrationMetadataMapper">
                    <resultMap id="bad" type="io.haifa.agent.store.sqlite.mybatis.MigrationRow">
                        <result column="version" property="version" typeHandler="missing.TypeHandler"/>
                    </resultMap>
                </mapper>
                """);
        assertThatThrownBy(() -> new SqliteMyBatisSessionFactory(1_024, List.of(unknownHandler)))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("Unable to parse");
    }

    @Test
    void unknownColumnsFailClosedAtExecution() {
        MapperXml badColumn = new MapperXml(
                "bad-column.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="io.haifa.agent.store.sqlite.mybatis.SqliteMyBatisSessionFactoryTest$BadColumnMapper">
                    <select id="read" resultType="java.lang.String">
                        SELECT missing_column FROM schema_migration
                    </select>
                </mapper>
                """);
        SqliteMyBatisSessionFactory myBatis = new SqliteMyBatisSessionFactory(1_024, List.of(badColumn));
        SqliteConnectionFactory connections = new SqliteConnectionFactory(SqliteTestSupport.configuration(directory));
        connections.initialize();
        new SqliteMigrationRunner(connections, SqliteTestSupport.CLOCK).migrate(RuntimeStoreMigrations.all());

        try (Connection connection = connections.openConnection();
                SqlSession session = myBatis.openSession(connection)) {
            assertThatThrownBy(() -> session.getMapper(BadColumnMapper.class).read())
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("missing_column");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String mapperXml(String sql) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="io.haifa.agent.store.sqlite.mybatis.MigrationMetadataMapper">
                    <select id="findByVersion" resultType="long">
                """
                + sql
                + """

                    </select>
                </mapper>
                """;
    }

    public interface BadColumnMapper {
        String read();
    }
}
