package io.haifa.agent.starter;

/**
 * Lifecycle expectation for one native MCP Client connection.
 *
 * <p>This is a per-connection declaration, not a product-wide capability framework: it only decides
 * whether an unusable MCP server fails the build or is skipped with a diagnostic.
 */
public enum McpServerRequirement {
    /** An unusable MCP server fails the Agent build. */
    REQUIRED,
    /** An unusable MCP server contributes no Tool and reports a safe diagnostic instead. */
    OPTIONAL
}
