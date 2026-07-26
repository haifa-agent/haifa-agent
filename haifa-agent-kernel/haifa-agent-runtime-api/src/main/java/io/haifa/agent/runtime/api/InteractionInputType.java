package io.haifa.agent.runtime.api;

import java.util.Set;

/** Forward-compatible bounded input shape. Unknown values are readable but not executable. */
public record InteractionInputType(String value) {
    public static final InteractionInputType NONE = new InteractionInputType("none");
    public static final InteractionInputType TEXT = new InteractionInputType("text");
    public static final InteractionInputType SINGLE_CHOICE = new InteractionInputType("single-choice");
    public static final InteractionInputType MULTI_CHOICE = new InteractionInputType("multi-choice");
    public static final InteractionInputType CONTENT_PARTS = new InteractionInputType("content-parts");
    public static final InteractionInputType SCHEMA_REF = new InteractionInputType("schema-ref");

    private static final Set<String> KNOWN = Set.of(
            NONE.value, TEXT.value, SINGLE_CHOICE.value, MULTI_CHOICE.value, CONTENT_PARTS.value, SCHEMA_REF.value);

    public InteractionInputType {
        value = InteractionKind.requireToken(value, "value");
    }

    public boolean known() {
        return KNOWN.contains(value);
    }
}
