package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.project.core.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileToolOperationsTest {
    @TempDir
    Path root;

    @BeforeEach
    void canonicalizeTempDirectory() throws IOException {
        root = root.toRealPath();
    }

    @Test
    void continuesBoundedReadsWithOpaqueVersionedCursor() throws Exception {
        Path file = root.resolve("large.txt");
        Files.writeString(file, "one\ntwo\nthree\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = file.toAbsolutePath().normalize().toString();
        var first = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", hostPath, "maxBytes", 8, "maxLines", 1)));
        String cursor = (String) first.structuredData().get("nextCursor");
        var second = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", hostPath, "cursor", cursor, "maxBytes", 8, "maxLines", 1)));

        assertThat(first.successful()).isTrue();
        assertThat(first.structuredData())
                .containsEntry("content", "one\n")
                .containsEntry("startLine", 1)
                .containsEntry("hasMore", true);
        assertThat(second.successful()).isTrue();
        assertThat(second.structuredData()).containsEntry("content", "two\n").containsEntry("startLine", 2);

        Files.writeString(file, "changed\ncontent\n", StandardCharsets.UTF_8);
        var stale = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", hostPath, "cursor", cursor)));
        assertThat(stale.successful()).isFalse();
        assertThat(stale.structuredData())
                .containsEntry("errorCode", "FILE_CURSOR_STALE")
                .containsEntry("failureActionCode", "RESTART_READ_FROM_CURRENT_VERSION")
                .containsEntry("retryable", true)
                .containsEntry("maximumAutomaticRetries", 1);
    }

    @Test
    void returnsStableFeedbackForInvalidFileArguments() {
        Fixture fixture = fixture();

        var result = fixture.operations.execute(
                "file.list",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("recursive", true)));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("stableFailureCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "READ_CURRENT_STATE")
                .containsEntry("retryable", false);
    }

    @Test
    void appliesModelVisibleContextPatchWithoutSeparatePathArgument() throws Exception {
        Path sourceFile = root.resolve("source.txt");
        Files.writeString(sourceFile, "anchor\nold\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = sourceFile.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of(
                        "patch",
                        """
                        *** Begin Patch
                        *** Update File: %s
                        @@ anchor
                        -old
                        +new
                        *** End Patch
                        """
                                .formatted(hostPath))));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("complete", true)
                .doesNotContainKeys("changeReviewArtifactRef", "artifactRef", "changeReviewArtifact", "changeSetIds");
        assertThat(result.structuredData().get("afterContentHash"))
                .isInstanceOfSatisfying(String.class, hash -> assertThat(hash).matches("sha256:[0-9a-f]{64}"));
        assertThat(result.structuredData().get("afterContentHashes"))
                .isInstanceOfSatisfying(Map.class, hashes -> assertThat(hashes)
                        .containsEntry(hostPath, result.structuredData().get("afterContentHash")));
        assertThat(Files.readString(sourceFile)).isEqualTo("anchor\nnew\n");
    }

    @Test
    void appliesUniquePatchBodyWhenOptionalNavigationHintDoesNotMatch() throws Exception {
        Path sourceFile = root.resolve("unique.txt");
        Files.writeString(sourceFile, "before\nold\nafter\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = sourceFile.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of(
                        "patch",
                        """
                        *** Begin Patch
                        *** Update File: %s
                        @@ descriptive navigation hint
                        -old
                        +new
                        *** End Patch
                        """
                                .formatted(hostPath))));

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(sourceFile)).isEqualTo("before\nnew\nafter\n");
    }

    @Test
    void rejectsAmbiguousPatchBodyInsteadOfChangingTheFirstMatch() throws Exception {
        Path sourceFile = root.resolve("ambiguous.txt");
        String original = "old\nbetween\nold\n";
        Files.writeString(sourceFile, original, StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = sourceFile.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of(
                        "patch",
                        """
                        *** Begin Patch
                        *** Update File: %s
                        @@ descriptive navigation hint
                        -old
                        +new
                        *** End Patch
                        """
                                .formatted(hostPath))));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "PATCH_CONFLICT")
                .containsEntry("stableFailureCode", "PATCH_AMBIGUOUS_MATCH")
                .containsEntry("hunkIndex", 0)
                .containsEntry("candidateCount", 2)
                .containsEntry("failureActionCode", "RE_READ_AND_REGENERATE_PATCH");
        assertThat(Files.readString(sourceFile)).isEqualTo(original);
    }

    @Test
    void usesUniqueNavigationHintToDisambiguateRepeatedPatchBody() throws Exception {
        Path sourceFile = root.resolve("scoped.txt");
        Files.writeString(sourceFile, "first section\nold\nsecond section\nold\n", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = sourceFile.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of(
                        "patch",
                        """
                        *** Begin Patch
                        *** Update File: %s
                        @@ second section
                        -old
                        +new
                        *** End Patch
                        """
                                .formatted(hostPath))));

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(sourceFile)).isEqualTo("first section\nold\nsecond section\nnew\n");
    }

    @Test
    void rejectsPureInsertionWithoutAUniqueLocation() throws Exception {
        Path sourceFile = root.resolve("insertion.txt");
        String original = "first\nsecond\n";
        Files.writeString(sourceFile, original, StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = sourceFile.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.patch",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of(
                        "patch",
                        """
                        *** Begin Patch
                        *** Update File: %s
                        @@
                        +inserted
                        *** End Patch
                        """
                                .formatted(hostPath))));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("stableFailureCode", "PATCH_AMBIGUOUS_MATCH")
                .containsEntry("candidateCount", 3);
        assertThat(Files.readString(sourceFile)).isEqualTo(original);
    }

    @Test
    void returnsKnownToolFailureWhenWorkspaceMutationIsRejected() throws IOException {
        Fixture fixture = fixture();
        Files.writeString(root.resolve("existing.txt"), "existing", StandardCharsets.UTF_8);
        String hostPath =
                root.resolve("existing.txt").toAbsolutePath().normalize().toString();

        var result = fixture.operations.execute(
                "file.create",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", hostPath, "content", "replacement")));

        assertThat(result.successful()).isFalse();
        assertThat(result.summary()).isEqualTo("Workspace mutation failed: TARGET_EXISTS (path=existing.txt)");
        assertThat(result.structuredData())
                .containsEntry("errorCode", "TARGET_EXISTS")
                .containsEntry("failureActionCode", "USE_FILE_WRITE_OR_PATCH")
                .containsEntry("retryable", false)
                .containsEntry("path", "existing.txt");
    }

    @Test
    void writesMissingTargetAndKeepsSensitivePathRecoveryActions() throws Exception {
        Fixture fixture = fixture();
        String missingPath =
                root.resolve("missing.txt").toAbsolutePath().normalize().toString();
        String sensitivePath = root.resolve(".env").toAbsolutePath().normalize().toString();

        var missingWrite = fixture.operations.execute(
                "file.write",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", missingPath, "content", "new")));
        var sensitiveRead = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", sensitivePath)));

        assertThat(missingWrite.successful()).isTrue();
        assertThat(missingWrite.summary()).isEqualTo("Created " + missingPath);
        assertThat(missingWrite.structuredData()).containsEntry("path", missingPath);
        assertThat(Files.readString(root.resolve("missing.txt"))).isEqualTo("new");
        assertThat(sensitiveRead.structuredData())
                .containsEntry("stableFailureCode", "SENSITIVE_PATH")
                .containsEntry("failureActionCode", "USER_ACTION_REQUIRED")
                .containsEntry("retryable", false)
                .containsEntry("maximumAutomaticRetries", 0);
        assertThat(sensitiveRead.structuredData().get("failureAction").toString())
                .contains("do not rename, relocate, or copy");
    }

    @Test
    void bindsFileMutationToTheToolCallAndWritesMatchingContent() throws Exception {
        Path tracked = root.resolve("tracked.txt");
        Files.writeString(tracked, "before", StandardCharsets.UTF_8);
        Fixture fixture = fixture();
        String hostPath = tracked.toAbsolutePath().normalize().toString();
        ToolArguments arguments = arguments(Map.of("path", hostPath, "content", "after"));

        var result = fixture.operations.execute(
                "file.write",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-reconcile",
                "tool-call-reconcile",
                "idempotency-reconcile",
                arguments);

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData().get("afterContentHash"))
                .isInstanceOfSatisfying(String.class, hash -> assertThat(hash).matches("sha256:[0-9a-f]{64}"));
        assertThat(Files.readString(tracked)).isEqualTo("after");
    }

    private Fixture fixture() {
        return fixture(null, false);
    }

    private Fixture fixture(InMemorySessionChangeLedger ledger) {
        return fixture(ledger, false);
    }

    private Fixture fixture(boolean workspaceAttachmentDisclosed) {
        return fixture(null, workspaceAttachmentDisclosed);
    }

    private Fixture fixture(InMemorySessionChangeLedger ledger, boolean workspaceAttachmentDisclosed) {
        var support = LocalFileToolTestSupport.createSingleRootFixture(root, ledger, workspaceAttachmentDisclosed);
        return new Fixture(support.workspaceId(), support.operations());
    }

    private static ToolArguments arguments(Map<String, Object> values) {
        return new ToolArguments("haifa.file.read.input", "1.1.0", values);
    }

    private record Fixture(WorkspaceId workspaceId, LocalFileToolOperations operations) {}

    @Test
    void rejectsRelativePathOrAlias() {
        Fixture fixture = fixture();
        var resultAlias = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", "unknown:file.txt")));

        assertThat(resultAlias.successful()).isFalse();
        assertThat(resultAlias.structuredData())
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ABSOLUTE_HOST_PATH");

        var resultRelative = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", "file.txt")));

        assertThat(resultRelative.successful()).isFalse();
        assertThat(resultRelative.structuredData())
                .containsEntry("errorCode", "INVALID_ARGUMENT")
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("failureActionCode", "USE_ABSOLUTE_HOST_PATH");
    }

    @Test
    void rejectsUnauthorizedHostPath() throws Exception {
        Fixture fixture = fixture();
        Path outside = root.resolveSibling("outside.txt").toAbsolutePath().normalize();
        var result = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", outside.toString())));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "ACCESS_DENIED")
                .containsEntry("failureCategory", "WORKSPACE_SCOPE_DENIED")
                .containsEntry("failureActionCode", "USE_AUTHORIZED_WORKSPACE_PATH");
    }

    @Test
    void requestsDirectoryAuthorizationForUnauthorizedHostPathWhenAttachmentIsDisclosed() throws Exception {
        Fixture fixture = fixture(true);
        Path outside = root.resolveSibling("outside.txt").toAbsolutePath().normalize();
        var result = fixture.operations.execute(
                "file.read",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", outside.toString())));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("errorCode", "ACCESS_DENIED")
                .containsEntry("failureCategory", "WORKSPACE_SCOPE_DENIED")
                .containsEntry("failureActionCode", "REQUEST_DIRECTORY_AUTHORIZATION");
    }

    @Test
    void deletesEmptyDirectory() throws Exception {
        Path testdir = root.resolve("testdir");
        Files.createDirectories(testdir);
        Fixture fixture = fixture();

        var result = fixture.operations.execute(
                "file.delete",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", testdir.toAbsolutePath().normalize().toString())));

        assertThat(result.successful()).isTrue();
        assertThat(Files.exists(testdir)).isFalse();
    }

    @Test
    void deletesRegularFileDirectlyWithoutQuarantineToken() throws Exception {
        Path trash = root.resolve("trash.txt");
        Files.writeString(trash, "delete me", StandardCharsets.UTF_8);
        Fixture fixture = fixture();

        String hostPath = trash.toAbsolutePath().normalize().toString();
        var result = fixture.operations.execute(
                "file.delete",
                fixture.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", hostPath)));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("path", hostPath)
                .doesNotContainKeys("quarantineToken", "changeSetId", "changeReviewArtifact");
        assertThat(Files.exists(trash)).isFalse();
    }

    @Test
    void recordsChangesIntoSessionLedger() {
        var ledger = new InMemorySessionChangeLedger();
        Fixture f = fixture(ledger);

        String hostPath = root.resolve("hello.txt").toAbsolutePath().normalize().toString();
        var createRes = f.operations.execute(
                "file.create",
                f.workspaceId,
                new PrincipalRef("operator", "user"),
                "run-1",
                arguments(Map.of("path", hostPath, "content", "hello world")));

        assertThat(createRes.successful()).isTrue();
        assertThat(createRes.structuredData().get("afterContentHash"))
                .isInstanceOfSatisfying(String.class, hash -> assertThat(hash).matches("sha256:[0-9a-f]{64}"));
        assertThat(ledger.compactedChanges(f.workspaceId)).hasSize(1);
        SessionFileChangeRecord record = ledger.compactedChanges(f.workspaceId).get(0);
        assertThat(record.path().projectPath().value()).isEqualTo("hello.txt");
    }
}
