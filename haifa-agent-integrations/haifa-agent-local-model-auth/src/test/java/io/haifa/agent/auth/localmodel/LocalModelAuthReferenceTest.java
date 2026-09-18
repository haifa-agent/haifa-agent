package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalModelAuthReferenceTest {
    @Test
    void parsesAndCanonicalizesProvider() {
        LocalModelAuthReference reference = LocalModelAuthReference.parse("model-auth://OpenAI-Codex/default");

        assertThat(reference.value()).isEqualTo("model-auth://openai-codex/default");
        assertThat(reference.providerId()).isEqualTo("openai-codex");
        assertThat(reference.slot()).isEqualTo("default");
    }

    @Test
    void rejectsTraversalExtraSegmentsAndUriDecoration() {
        assertThatThrownBy(() -> LocalModelAuthReference.parse("model-auth://openai/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalModelAuthReference.parse("model-auth://openai/default?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalModelAuthReference.parse("model-auth://openai/default#x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalModelAuthReference.parse("coding-auth://openai/default"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalModelAuthReference("model-auth://openai/default", "other", "default"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
