package io.haifa.agent.model.openai.responses;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;

/** Validated Responses compatibility profiles and dialect registry. */
public final class OpenAiResponsesDialects {
    public static final String STANDARD = ModelApiBindingDefinition.STANDARD_DIALECT;
    public static final String DEEPSEEK = "deepseek-openai-responses";
    public static final String ALIYUN_BAILIAN = "aliyun-bailian-openai-responses";
    public static final String OPENAI_CODEX = "openai-codex-responses";
    static final String CODEX_ORIGINATOR_OPTION = "codex_originator";
    static final String CODEX_USER_AGENT_OPTION = "codex_user_agent";

    private OpenAiResponsesDialects() {}

    static OpenAiResponsesDialect resolve(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())
                || !ModelApiStyles.OPENAI_RESPONSES_ADAPTER.equals(snapshot.adapterType())) {
            throw new IllegalArgumentException("snapshot is not bound to the OpenAI Responses adapter");
        }
        OpenAiResponsesDialect dialect =
                switch (snapshot.dialect()) {
                    case STANDARD -> StandardOpenAiResponsesDialect.INSTANCE;
                    case DEEPSEEK -> DeepSeekOpenAiResponsesDialect.INSTANCE;
                    case ALIYUN_BAILIAN -> AliyunBailianOpenAiResponsesDialect.INSTANCE;
                    case OPENAI_CODEX -> OpenAiCodexResponsesDialect.INSTANCE;
                    default ->
                        throw new IllegalArgumentException(
                                "unsupported OpenAI Responses dialect: " + snapshot.dialect());
                };
        dialect.validateSnapshot(snapshot, allowInsecureHttp);
        return dialect;
    }
}
