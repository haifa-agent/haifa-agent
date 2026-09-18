package io.haifa.agent.testing.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretPreflightTest {
    @Test
    void resolvesDistinctSecretsWithoutRenderingTheirValues() {
        String secret = "test-secret-must-not-be-rendered";

        SecretPreflight.ResolvedSecrets resolved =
                SecretPreflight.require(Map.of("MODEL_KEY", secret), List.of("MODEL_KEY", "MODEL_KEY"));

        assertEquals(List.of(secret), List.copyOf(resolved.values()));
        assertEquals(java.util.Set.of("MODEL_KEY"), resolved.names());
        assertEquals(secret, resolved.value("MODEL_KEY"));
        assertFalse(resolved.toString().contains(secret));
    }

    @Test
    void reportsAllMissingNamesBeforeExecution() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SecretPreflight.require(Map.of("BLANK_KEY", " "), List.of("MODEL_KEY", "BLANK_KEY")));

        assertTrue(exception.getMessage().contains("MODEL_KEY"));
        assertTrue(exception.getMessage().contains("BLANK_KEY"));
    }

    @Test
    void acceptsSuiteWithoutRequiredSecrets() {
        SecretPreflight.ResolvedSecrets resolved = SecretPreflight.require(Map.of(), List.of());

        assertTrue(resolved.names().isEmpty());
        assertTrue(resolved.values().isEmpty());
    }
}
