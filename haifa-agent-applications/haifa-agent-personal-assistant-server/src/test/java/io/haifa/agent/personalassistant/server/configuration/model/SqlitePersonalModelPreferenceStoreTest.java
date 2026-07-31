package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePersonalModelPreferenceStoreTest {
    @TempDir
    Path directory;

    @Test
    void restoresSelectionAndProtectsRevisionAndIdempotency() {
        Path database = directory.resolve("personal.sqlite");
        var first = new SqlitePersonalModelPreferenceStore(database, "tenant", "principal");
        first.create("conversation-1", "deepseek", Instant.EPOCH);
        var changed = first.change("conversation-1", 0, "bailian", "key-a", "request-a", Instant.ofEpochSecond(1));

        var reopened = new SqlitePersonalModelPreferenceStore(database, "tenant", "principal");
        assertThat(reopened.find("conversation-1")).contains(changed);
        assertThat(reopened.change("conversation-1", 0, "bailian", "key-a", "request-a", Instant.ofEpochSecond(2)))
                .isEqualTo(changed);
        assertThatThrownBy(() -> reopened.change(
                        "conversation-1", 1, "deepseek", "key-a", "request-b", Instant.ofEpochSecond(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODEL_IDEMPOTENCY_CONFLICT");
    }
}
