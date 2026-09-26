package io.haifa.agent.memory.api;

/**
 * Memory bucket kind. {@code USER} targets the owner principal, {@code AGENT} an Agent Definition id, and
 * {@code SESSION} one conversation session.
 */
public enum MemoryScopeType {
    USER,
    AGENT,
    SESSION
}
