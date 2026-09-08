package io.haifa.agent.store.sqlite.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HaifaAgentStoreMigrationsContractTest {
    private static final String RESOURCE_ROOT = "/io/haifa/agent/store/sqlite/migration/";

    @Test
    void sharedRegistryIsTheOnlyProductionMigrationRegistry() throws Exception {
        Class<?> registry = Class.forName("io.haifa.agent.store.sqlite.migration.HaifaAgentStoreMigrations");
        assertThat(registry.getMethod("all").invoke(null)).isInstanceOf(List.class);
        assertThatThrownBy(() -> Class.forName("io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void cleanResourcesOmitV3AndV1008AndPublishOneNeutralArtifact() {
        assertThat(getClass().getResource(RESOURCE_ROOT + "V3__policy_approval_security.sql"))
                .isNull();
        assertThat(getClass().getResource(RESOURCE_ROOT + "V1007__coding_workspace_registry.sql"))
                .isNotNull();
        assertThat(getClass().getResource(RESOURCE_ROOT + "V1008__coding_workspace_access.sql"))
                .isNull();
        assertThat(getClass().getResource(RESOURCE_ROOT + "haifa-agent-v1.0-init.sql"))
                .isNotNull();
    }

    @Test
    void legacyPolicyMapperTypesAreAbsent() {
        for (String type : List.of(
                "io.haifa.agent.store.sqlite.mybatis.PolicyStoreMapper",
                "io.haifa.agent.store.sqlite.mybatis.PolicySnapshotRow",
                "io.haifa.agent.store.sqlite.mybatis.PolicyDecisionRow",
                "io.haifa.agent.store.sqlite.mybatis.PolicyAuthorizationEvidenceRow",
                "io.haifa.agent.store.sqlite.mybatis.ApprovalGrantRow",
                "io.haifa.agent.store.sqlite.mybatis.ProjectTrustRow",
                "io.haifa.agent.store.sqlite.mybatis.ApprovalRequestMetadataRow")) {
            assertThatThrownBy(() -> Class.forName(type)).as(type).isInstanceOf(ClassNotFoundException.class);
        }
        assertThat(getClass().getResource("/io/haifa/agent/store/sqlite/mybatis/PolicyStoreMapper.xml"))
                .isNull();
    }
}
