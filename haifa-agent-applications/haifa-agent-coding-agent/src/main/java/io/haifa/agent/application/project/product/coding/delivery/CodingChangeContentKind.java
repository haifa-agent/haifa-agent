package io.haifa.agent.application.project.product.coding.delivery;

/** Safe content classification for deterministic change review; no file body is retained. */
public enum CodingChangeContentKind {
    TEXT,
    BINARY,
    OVERSIZE,
    OPAQUE
}
