package io.haifa.agent.store.sqlite.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import org.junit.jupiter.api.Test;

class StableValueCodecTest {
    @Test
    void enumAndIdentifierCodecsFailClosed() {
        StableEnumCodec<ExampleState> enums = new StableEnumCodec<>(ExampleState.class);
        StringIdentifierCodec<AgentRunId> ids = new StringIdentifierCodec<>(AgentRunId::value, AgentRunId::new);

        assertThat(enums.decode(enums.encode(ExampleState.ACTIVE))).isEqualTo(ExampleState.ACTIVE);
        assertThat(ids.decode(ids.encode(new AgentRunId("run-1")))).isEqualTo(new AgentRunId("run-1"));

        assertThatThrownBy(() -> enums.decode("FUTURE")).isInstanceOf(PayloadCodecException.class);
        assertThatThrownBy(() -> ids.decode(" ")).isInstanceOf(PayloadCodecException.class);
    }

    private enum ExampleState {
        ACTIVE
    }
}
