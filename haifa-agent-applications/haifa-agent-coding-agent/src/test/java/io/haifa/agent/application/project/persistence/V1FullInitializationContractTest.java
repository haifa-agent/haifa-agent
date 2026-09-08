package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.store.sqlite.migration.SqlScriptParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1FullInitializationContractTest {
    private static final String FULL_INITIALIZATION_RESOURCE =
            "/io/haifa/agent/store/sqlite/migration/haifa-agent-v1.0-init.sql";
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("alice", "user");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace-a");

    @TempDir
    Path directory;

    @Test
    void codingAssemblyReopensTheSharedV10ArtifactAndRoundTripsWorkspaceAccess() throws Exception {
        Path database = directory.resolve("coding-v1.db");
        initializeArtifact(database);
        IdentifierGenerator identifiers = identifiers();

        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database), Clock.systemUTC(), identifiers, null)) {
            first.workspaceAccess()
                    .replace(new WorkspaceAccess(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.DEVELOP));
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database), Clock.systemUTC(), identifiers, null)) {
            assertThat(reopened.workspaceAccess().find(TENANT, PRINCIPAL, WORKSPACE))
                    .contains(new WorkspaceAccess(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.DEVELOP));
        }
    }

    private static void initializeArtifact(Path database) throws Exception {
        String script;
        try (InputStream input =
                V1FullInitializationContractTest.class.getResourceAsStream(FULL_INITIALIZATION_RESOURCE)) {
            assertThat(input).as("shared V1.0 initialization SQL resource").isNotNull();
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            for (String sql : SqlScriptParser.parse(script)) {
                statement.execute(sql);
            }
        }
    }

    private static IdentifierGenerator identifiers() {
        AtomicInteger sequence = new AtomicInteger();
        return () -> "v1-contract-" + sequence.incrementAndGet();
    }
}
