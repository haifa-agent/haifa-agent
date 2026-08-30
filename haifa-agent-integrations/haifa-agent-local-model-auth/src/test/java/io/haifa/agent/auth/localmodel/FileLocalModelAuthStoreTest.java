package io.haifa.agent.auth.localmodel;

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
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLocalModelAuthStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStrictApiAndExternalCredentialsOnlyInDedicatedFile() throws Exception {
        FileLocalModelAuthStore store = store();
        long now = Instant.parse("2026-08-23T00:00:00Z").toEpochMilli();
        LocalModelAuthReference apiReference = LocalModelAuthReference.parse("model-auth://openai-api/default");
        LocalModelAuthReference codexReference = LocalModelAuthReference.parse("model-auth://openai-codex/default");
        StoredExternalCredential oauth = new StoredExternalCredential(
                codexReference,
                new ExternalLoginMethodId("openai-codex"),
                "registration",
                "access-canary",
                "refresh-canary",
                now + 3_600_000,
                now,
                "account-1");

        store.save(new StoredApiKeyCredential(apiReference, "api-canary"));
        store.save(oauth);

        assertThat(store.find(apiReference))
                .get()
                .isInstanceOfSatisfying(StoredApiKeyCredential.class, actual -> assertThat(actual.apiKey())
                        .isEqualTo("api-canary"));
        assertThat(store.find(codexReference)).get().isInstanceOfSatisfying(StoredExternalCredential.class, actual -> {
            assertThat(actual.accessToken()).isEqualTo("access-canary");
            assertThat(actual.refreshToken()).isEqualTo("refresh-canary");
            assertThat(actual.toString()).doesNotContain("access-canary", "refresh-canary");
        });
        String raw = Files.readString(store.file());
        assertThat(raw).contains("api-canary", "access-canary", "refresh-canary", "method_id");
        assertPrivatePermissions(store.file(), false);
        assertPrivatePermissions(store.file().getParent(), true);
    }

    @Test
    void corruptedJsonFailsClosedAndIsNotOverwritten() throws Exception {
        FileLocalModelAuthStore store = store();
        LocalModelAuthReference first = LocalModelAuthReference.parse("model-auth://openai-api/first");
        store.save(new StoredApiKeyCredential(first, "first-secret"));
        Files.writeString(store.file(), "{broken-json", StandardOpenOption.TRUNCATE_EXISTING);
        byte[] corrupted = Files.readAllBytes(store.file());

        assertThatThrownBy(() -> store.save(new StoredApiKeyCredential(
                        LocalModelAuthReference.parse("model-auth://openai-api/second"), "second-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupted");
        assertThat(Files.readAllBytes(store.file())).isEqualTo(corrupted);
    }

    @Test
    void refusesConcurrentMutationAndPreservesExistingCredential() throws Exception {
        FileLocalModelAuthStore store = store();
        LocalModelAuthReference reference = LocalModelAuthReference.parse("model-auth://openai-api/default");
        store.save(new StoredApiKeyCredential(reference, "first-secret"));
        Path lockPath = store.file().resolveSibling("auth.json.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            assertThatThrownBy(() -> store.save(new StoredApiKeyCredential(reference, "replacement-secret")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("locked");
        }
        assertThat(store.find(reference))
                .get()
                .isInstanceOfSatisfying(StoredApiKeyCredential.class, actual -> assertThat(actual.apiKey())
                        .isEqualTo("first-secret"));
    }

    @Test
    void deletesOnlySelectedEntryAndRemovesEmptyAuthFile() {
        FileLocalModelAuthStore store = store();
        LocalModelAuthReference one = LocalModelAuthReference.parse("model-auth://openai-api/one");
        LocalModelAuthReference two = LocalModelAuthReference.parse("model-auth://openai-api/two");
        store.save(new StoredApiKeyCredential(one, "first"));
        store.save(new StoredApiKeyCredential(two, "second"));

        assertThat(store.delete(one)).isTrue();
        assertThat(store.find(two)).isPresent();
        assertThat(Files.exists(store.file())).isTrue();
        assertThat(store.delete(two)).isTrue();
        assertThat(Files.exists(store.file())).isFalse();
    }

    private FileLocalModelAuthStore store() {
        return new FileLocalModelAuthStore(temporaryDirectory.resolve("auth.json"), json);
    }

    private static void assertPrivatePermissions(Path path, boolean directory) throws Exception {
        var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> expected = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            assertThat(Files.getPosixFilePermissions(path)).isEqualTo(expected);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        UserPrincipal current = path.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        assertThat(acl).isNotNull();
        assertThat(acl.getAcl()).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.principal()).isEqualTo(current);
        });
    }
}
