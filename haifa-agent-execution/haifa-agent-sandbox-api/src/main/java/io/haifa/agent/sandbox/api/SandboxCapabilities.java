package io.haifa.agent.sandbox.api;

/**
 * Declares the controlled host-execution capability a provider actually delivers.
 *
 * <p>Kernel, container and namespace level isolation are deliberately out of scope; see
 * {@code docs/34-sandbox-simplification-and-host-execution-design.md}.
 */
public record SandboxCapabilities(boolean processTreeTermination) {}
