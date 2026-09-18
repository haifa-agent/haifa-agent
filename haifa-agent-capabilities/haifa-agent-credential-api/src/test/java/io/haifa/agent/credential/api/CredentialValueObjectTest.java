package io.haifa.agent.credential.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialValueObjectTest {
    @Test
    void requirementPreservesCredentialIdAndRejectsBlank() {
        var requirement = new CredentialRequirement("test-api-key");
        assertEquals("test-api-key", requirement.credentialId());

        assertThrows(IllegalArgumentException.class, () -> new CredentialRequirement(""));
        assertThrows(IllegalArgumentException.class, () -> new CredentialRequirement("   "));
        assertThrows(IllegalArgumentException.class, () -> new CredentialRequirement(null));
    }

    @Test
    void brokerDefaultMethodsWorkAsExpected() {
        CredentialBroker broker = credentialId -> Optional.empty();
        assertThrows(CredentialException.class, () -> broker.requireSecret("missing-key"));
        assertNotNull(broker.redactor());
        assertEquals("text", broker.redactor().redact("text"));
    }

    @Test
    void exceptionPreservesMessageAndCause() {
        var cause = new RuntimeException("root");
        var ex = new CredentialException("failed", cause);
        assertEquals("failed", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}
