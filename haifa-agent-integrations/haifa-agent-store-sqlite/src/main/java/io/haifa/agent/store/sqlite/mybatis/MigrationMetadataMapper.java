package io.haifa.agent.store.sqlite.mybatis;

import java.util.Optional;
import org.apache.ibatis.annotations.Param;

public interface MigrationMetadataMapper {
    Optional<MigrationRow> findByVersion(@Param("version") long version);
}
