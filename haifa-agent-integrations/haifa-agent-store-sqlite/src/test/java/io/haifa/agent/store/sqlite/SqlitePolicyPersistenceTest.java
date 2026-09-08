package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePolicyPersistenceTest {
    private static final List<String> LEGACY_TABLES = List.of(
            "policy_snapshot",
            "policy_decision",
            "policy_authorization_evidence",
            "approval_grant",
            "project_trust",
            "approval_request_metadata",
            "approval_response_metadata");

    @Test
    void cleanBaselineDoesNotCreateLegacyPolicyPersistenceFamily(@TempDir Path directory) throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
                Connection connection = foundation.connections().openConnection()) {
            for (String table : LEGACY_TABLES) {
                assertThat(tableExists(connection, table)).as(table).isFalse();
            }
        }
    }

    @Test
    void productionFoundationDoesNotExposeLegacyPolicyStores() {
        assertThat(List.of(SqliteStoreFoundation.class.getMethods()))
                .extracting(method -> method.getName())
                .doesNotContain(
                        "policySnapshots",
                        "policyDecisions",
                        "policyAuthorizationEvidence",
                        "approvalGrants",
                        "projectTrusts");
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement =
                connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
