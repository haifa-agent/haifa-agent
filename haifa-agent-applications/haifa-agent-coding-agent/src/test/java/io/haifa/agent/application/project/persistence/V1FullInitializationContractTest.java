package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryEntry;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStatus;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryIdentity;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.store.sqlite.migration.SqlScriptParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V1FullInitializationContractTest {
    private static final String FULL_INITIALIZATION_RESOURCE =
            "/io/haifa/agent/store/sqlite/migration/haifa-agent-v1.0-init.sql";
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("alice", "user");
    private static final ProjectId PROJECT = new ProjectId("project-a");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace-a");

    @TempDir
    Path directory;

    @Test
    void codingAssemblyReopensTheSharedV10ArtifactAndRoundTripsAuthorizedDirectory() throws Exception {
        Path database = directory.resolve("coding-v1.db");
        initializeArtifact(database);
        IdentifierGenerator identifiers = identifiers();
        Path root = Files.createDirectory(directory.resolve("authorized"));
        String physicalFingerprint = HostDirectoryIdentity.resolve(root).physicalFingerprint();

        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database), Clock.systemUTC(), identifiers, null)) {
            first.authorizedDirectories()
                    .create(AuthorizedDirectoryEntry.active(
                            PROJECT,
                            WORKSPACE,
                            TENANT,
                            PRINCIPAL,
                            WorkspaceAccessMode.DEVELOP,
                            "authorized",
                            root,
                            physicalFingerprint,
                            Instant.ofEpochMilli(0)));
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database), Clock.systemUTC(), identifiers, null)) {
            assertThat(reopened.authorizedDirectories().find(PROJECT, WORKSPACE))
                    .get()
                    .satisfies(entry -> {
                        assertThat(entry.mode()).isEqualTo(WorkspaceAccessMode.DEVELOP);
                        assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
                        assertThat(entry.realPath()).isEqualTo(root.toRealPath(LinkOption.NOFOLLOW_LINKS));
                        assertThat(entry.physicalFingerprint()).isEqualTo(physicalFingerprint);
                    });
        }
    }

    @Test
    void sharedV10ArtifactPublishesOnlyTheSingleAuthorizedDirectoryTable() throws Exception {
        Path database = directory.resolve("schema.db");
        initializeArtifact(database);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "coding_authorized_directory")).isTrue();
            assertThat(tableExists(statement, "coding_workspace_registry")).isFalse();
            assertThat(tableExists(statement, "coding_workspace_access")).isFalse();
        }
    }

    private static boolean tableExists(Statement statement, String table) throws Exception {
        try (var result =
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return result.next();
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
