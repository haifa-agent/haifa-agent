package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@Tag("live")
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialManagerLiveIT {
    private String testTargetName;
    private WindowsCredentialManagerClient client;

    @BeforeEach
    void setUp() {
        client = NativeWindowsCredentialManagerClient.INSTANCE;
        testTargetName = "haifa:live-test:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (client != null && testTargetName != null) {
            try {
                client.delete(testTargetName);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
    }

    @Test
    void realWindowsCredentialLifecycle() {
        // 1. Initial read should be empty
        Optional<String> initial = client.read(testTargetName);
        assertThat(initial).isEmpty();

        // 2. Write credential
        String payload = "{\"test\":\"live-credential-value\"}";
        client.write(testTargetName, payload);

        // 3. Read back written credential
        Optional<String> readBack = client.read(testTargetName);
        assertThat(readBack).contains(payload);

        // 4. Enumerate targets and verify prefix search
        List<String> targets = client.listTargets("haifa:live-test:");
        assertThat(targets).contains(testTargetName);

        // 5. Update credential
        String updatedPayload = "{\"test\":\"updated-live-value\"}";
        client.write(testTargetName, updatedPayload);
        Optional<String> updatedRead = client.read(testTargetName);
        assertThat(updatedRead).contains(updatedPayload);

        // 6. Delete credential
        boolean deleted = client.delete(testTargetName);
        assertThat(deleted).isTrue();

        // 7. Verify read after deletion
        Optional<String> afterDelete = client.read(testTargetName);
        assertThat(afterDelete).isEmpty();

        // 8. Subsequent delete returns false
        boolean secondDelete = client.delete(testTargetName);
        assertThat(secondDelete).isFalse();
    }
}
