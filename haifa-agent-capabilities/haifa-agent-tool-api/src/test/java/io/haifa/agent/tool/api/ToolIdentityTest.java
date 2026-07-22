package io.haifa.agent.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToolIdentityTest {
    @Test
    void acceptsCanonicalNamesAliasesProvidersAndSemanticVersions() {
        assertEquals("workspace.file.read", new ToolName("workspace.file.read").value());
        assertEquals("file_read", new ToolAlias("file_read").value());
        assertEquals("builtin.project", new ToolProviderId("builtin.project").value());
        assertEquals("1.2.3-alpha.1+build.7", new SemanticVersion("1.2.3-alpha.1+build.7").value());
    }

    @Test
    void rejectsMalformedNamesAliasesProvidersAndSemanticVersions() {
        assertThrows(IllegalArgumentException.class, () -> new ToolName("workspace/file/read"));
        assertThrows(IllegalArgumentException.class, () -> new ToolAlias("file read"));
        assertThrows(IllegalArgumentException.class, () -> new ToolProviderId("mcp/server"));
        assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("1.0"));
        assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("01.0.0"));
        assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("1.0.0-01"));
        assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("1.0.0-alpha..1"));
    }
}
