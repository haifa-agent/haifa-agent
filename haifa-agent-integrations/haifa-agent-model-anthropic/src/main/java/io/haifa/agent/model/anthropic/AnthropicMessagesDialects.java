package io.haifa.agent.model.anthropic;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;

/** Validated Anthropic Messages compatibility profiles and dialect registry. */
public final class AnthropicMessagesDialects {
    public static final String STANDARD = ModelApiBindingDefinition.STANDARD_DIALECT;
    public static final String DEEPSEEK = "deepseek-anthropic-messages";
    public static final String ZHIPU = "zhipu-anthropic-messages";

    private AnthropicMessagesDialects() {}

    static AnthropicMessagesDialect resolve(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!ModelApiStyles.ANTHROPIC_MESSAGES.equals(snapshot.apiStyle())
                || !ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER.equals(snapshot.adapterType())) {
            throw new IllegalArgumentException("snapshot is not bound to the Anthropic Messages adapter");
        }
        AnthropicMessagesDialect dialect =
                switch (snapshot.dialect()) {
                    case STANDARD -> StandardAnthropicMessagesDialect.INSTANCE;
                    case DEEPSEEK -> DeepSeekAnthropicMessagesDialect.INSTANCE;
                    case ZHIPU -> ZhipuAnthropicMessagesDialect.INSTANCE;
                    default ->
                        throw new IllegalArgumentException(
                                "unsupported Anthropic Messages dialect: " + snapshot.dialect());
                };
        dialect.validateSnapshot(snapshot, allowInsecureHttp);
        return dialect;
    }
}
