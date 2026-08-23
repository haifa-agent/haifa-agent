package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAuthFileStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsPlaintextApiAndOAuthCredentialsOnlyInTheDedicatedFile() throws Exception {
        CodingAuthFileStore store = store();
        long now = Instant.parse("2026-08-23T00:00:00Z").toEpochMilli();
        CodingAuthCredential oauth = CodingAuthCredential.oauth2(
                "openai-codex/default",
                "access-canary",
                "refresh-canary",
                now + 3_600_000,
                "account-1",
                "pi-local-compat",
                now);

        store.save(CodingAuthCredential.apiKey("openai-api/default", "api-canary"));
        store.save(oauth);

        assertThat(store.find("openai-api/default"))
                .get()
                .extracting(CodingAuthCredential::apiKey)
                .isEqualTo("api-canary");
        assertThat(store.find("openai-codex/default")).get().satisfies(actual -> {
            assertThat(actual.accessToken()).isEqualTo("access-canary");
            assertThat(actual.refreshToken()).isEqualTo("refresh-canary");
            assertThat(actual.accountId()).isEqualTo("account-1");
            assertThat(actual.toString()).doesNotContain("access-canary", "refresh-canary");
        });
        String raw = Files.readString(store.file());
        assertThat(raw).contains("api-canary", "access-canary", "refresh-canary");
        assertThat(Files.exists(temporaryDirectory.resolve("runtime.sqlite"))).isFalse();
        assertPrivatePermissions(store.file(), false);
        assertPrivatePermissions(store.file().getParent(), true);
    }

    @Test
    void corruptedJsonFailsClosedAndIsNotOverwrittenBySave() throws Exception {
        CodingAuthFileStore store = store();
        store.save(CodingAuthCredential.apiKey("openai-api/default", "first-secret"));
        Files.writeString(store.file(), "{broken-json", StandardOpenOption.TRUNCATE_EXISTING);
        byte[] corrupted = Files.readAllBytes(store.file());

        assertThatThrownBy(() -> store.save(CodingAuthCredential.apiKey("openai-api/second", "second-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupted");
        assertThat(Files.readAllBytes(store.file())).isEqualTo(corrupted);
    }

    @Test
    void rejectsUnexpectedCredentialFieldsInsteadOfSilentlyDroppingThem() throws Exception {
        CodingAuthFileStore store = store();
        store.save(CodingAuthCredential.apiKey("openai-api/default", "secret"));
        Files.writeString(
                store.file(),
                """
                {"version":1,"credentials":{"openai-api/default":{
                  "kind":"API_KEY","api_key":"secret","unexpected":"value"}}}
                """,
                StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(store::list)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected fields");
    }

    @Test
    void refusesConcurrentFileMutationAndLeavesExistingCredentialUntouched() throws Exception {
        CodingAuthFileStore store = store();
        store.save(CodingAuthCredential.apiKey("openai-api/default", "first-secret"));
        Path lockPath = store.file().resolveSibling("auth.json.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            assertThatThrownBy(
                            () -> store.save(CodingAuthCredential.apiKey("openai-api/default", "replacement-secret")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("locked");
        }
        assertThat(store.find("openai-api/default"))
                .get()
                .extracting(CodingAuthCredential::apiKey)
                .isEqualTo("first-secret");
    }

    @Test
    void logoutDeletesOnlyTheSelectedEntryAndRemovesAnEmptyAuthFile() {
        CodingAuthFileStore store = store();
        store.save(CodingAuthCredential.apiKey("openai-api/one", "first"));
        store.save(CodingAuthCredential.apiKey("openai-api/two", "second"));

        assertThat(store.delete("openai-api/one")).isTrue();
        assertThat(store.find("openai-api/two")).isPresent();
        assertThat(Files.exists(store.file())).isTrue();
        assertThat(store.delete("openai-api/two")).isTrue();
        assertThat(Files.exists(store.file())).isFalse();
        assertThat(Files.exists(temporaryDirectory.resolve("auth.json.lock"))).isTrue();
    }

    private CodingAuthFileStore store() {
        return new CodingAuthFileStore(temporaryDirectory.resolve("auth.json"), json);
    }

    private static void assertPrivatePermissions(Path path, boolean directory) throws Exception {
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> expected = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            assertThat(Files.getPosixFilePermissions(path)).isEqualTo(expected);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assertThat(acl).isNotNull();
        assertThat(acl.getAcl()).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.principal()).isEqualTo(Files.getOwner(path));
        });
    }
}
