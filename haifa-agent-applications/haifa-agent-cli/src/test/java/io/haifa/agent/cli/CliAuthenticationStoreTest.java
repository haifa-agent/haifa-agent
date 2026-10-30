package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.WindowsLocalModelAuthStore;
import org.junit.jupiter.api.Test;

class CliAuthenticationStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void defaultDoesNotSilentlyFallBackToPlaintextOnLinux() {
        assertThat(LocalCodingAgent.authenticationStore(json, "Linux", name -> null))
                .isInstanceOf(WindowsLocalModelAuthStore.class);
    }

    @Test
    void explicitlyConfiguredHeadlessLinuxUsesTheExistingFileStore() {
        assertThat(LocalCodingAgent.authenticationStore(json, "Linux", name -> "file"))
                .isInstanceOf(FileLocalModelAuthStore.class);
    }

    @Test
    void fileStoreCannotBeSelectedOnWindowsOrMac() {
        for (String os : new String[] {"Windows 11", "Mac OS X"}) {
            assertThatThrownBy(() -> LocalCodingAgent.authenticationStore(json, os, name -> "file"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unknownStoreDoesNotDowngradeToAnotherStore() {
        assertThatThrownBy(() -> LocalCodingAgent.authenticationStore(json, "Linux", name -> "keychain"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
