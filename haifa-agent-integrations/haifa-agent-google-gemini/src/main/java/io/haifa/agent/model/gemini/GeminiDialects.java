package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ModelApiBindingDefinition;

/** Governed Gemini transport dialect identifiers. */
public final class GeminiDialects {
    public static final String STANDARD = ModelApiBindingDefinition.STANDARD_DIALECT;
    public static final String CLIPROXYAPI_ANTIGRAVITY = "cliproxyapi-antigravity";
    public static final String ANTIGRAVITY_DIRECT = "antigravity-direct";

    private GeminiDialects() {}
}
