package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;

/** Governed Gemini transport dialect identifiers and registry. */
public final class GeminiDialects {
    public static final String STANDARD = ModelApiBindingDefinition.STANDARD_DIALECT;
    public static final String ANTIGRAVITY_DIRECT = "antigravity-direct";

    private GeminiDialects() {}

    static GeminiDialect resolve(
            ResolvedModelSnapshot snapshot, boolean allowInsecureLoopback, boolean allowStandardLoopbackStub) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT.equals(snapshot.apiStyle())
                || !ModelApiStyles.GOOGLE_GEMINI_ADAPTER.equals(snapshot.adapterType())) {
            throw new IllegalArgumentException("snapshot is not bound to the Gemini adapter");
        }
        GeminiDialect dialect =
                switch (snapshot.dialect()) {
                    case STANDARD -> StandardGeminiDialect.INSTANCE;
                    case ANTIGRAVITY_DIRECT -> AntigravityDirectGeminiDialect.INSTANCE;
                    default -> throw new IllegalArgumentException("unsupported Gemini dialect: " + snapshot.dialect());
                };
        dialect.validateSnapshot(snapshot, allowInsecureLoopback, allowStandardLoopbackStub);
        return dialect;
    }
}
