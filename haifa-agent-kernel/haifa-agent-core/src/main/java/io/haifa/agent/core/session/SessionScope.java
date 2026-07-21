package io.haifa.agent.core.session;

/** Ownership and longevity boundary of an Agent session. */
public enum SessionScope {
    EPHEMERAL,
    USER,
    PROJECT,
    TEAM,
    TENANT
}
