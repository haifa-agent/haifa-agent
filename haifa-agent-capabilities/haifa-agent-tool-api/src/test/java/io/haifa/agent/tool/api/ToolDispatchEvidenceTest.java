package io.haifa.agent.tool.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ToolDispatchEvidenceTest {
    @Test
    void acceptsOnlyBoundedIdentityAndARealSafeDigest() {
        assertDoesNotThrow(() -> new ToolDispatchEvidence("execution-1", OptionalLong.of(42), "a".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolDispatchEvidence("execution-1", OptionalLong.empty(), "workspace/src"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolDispatchEvidence("execution-1", OptionalLong.of(0), "a".repeat(64)));
    }
}
