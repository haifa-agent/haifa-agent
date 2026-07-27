package io.haifa.agent.application.project.persistence;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import io.haifa.agent.store.sqlite.mybatis.MapperXml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class ProjectApplicationMappers {
    private static final String CODING_SESSION_MAPPER =
            "/io/haifa/agent/application/project/persistence/CodingSessionMapper.xml";
    private static final String PRODUCT_SESSION_MAPPER =
            "/io/haifa/agent/application/project/persistence/ProjectProductSessionMapper.xml";

    private ProjectApplicationMappers() {}

    static List<MapperXml> all() {
        return List.of(read(PRODUCT_SESSION_MAPPER), read(CODING_SESSION_MAPPER));
    }

    private static MapperXml read(String resource) {
        try (InputStream input = ProjectApplicationMappers.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MAPPER_CONFIGURATION_FAILED,
                        "Bundled Project Application mapper is missing");
            }
            return new MapperXml(resource, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MAPPER_CONFIGURATION_FAILED,
                    "Unable to read bundled Project Application mapper",
                    exception);
        }
    }
}
