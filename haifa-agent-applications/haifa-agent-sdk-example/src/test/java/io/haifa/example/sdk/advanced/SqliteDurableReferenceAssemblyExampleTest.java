package io.haifa.example.sdk.advanced;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.nio.file.Path;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDurableReferenceAssemblyExampleTest {
    @Test
    void persistsAndReopensConversationState(@TempDir Path directory) throws Exception {
        var key = new SecretKeySpec(new byte[32], "AES");
        String sessionId;
        try (var first = SqliteDurableReferenceAssemblyExample.open(
                directory,
                key,
                DeterministicExampleSupport.model("persisted-answer"),
                DeterministicExampleSupport.snapshot(),
                SdkCallerProvider.defaultPublicUser())) {
            var conversation = first.conversations()
                    .start(new StartConversationCommand("sqlite-start", "SQLite", "Persist this conversation."));
            first.runs().await(conversation.activeRunId().orElseThrow());
            sessionId = conversation.sessionId().value();
        }

        try (var reopened = SqliteDurableReferenceAssemblyExample.open(
                directory,
                key,
                DeterministicExampleSupport.model("reopened-answer"),
                DeterministicExampleSupport.snapshot(),
                SdkCallerProvider.defaultPublicUser())) {
            assertThat(reopened.conversations()
                            .list(io.haifa.agent.sdk.conversation.ConversationQuery.active(10))
                            .items())
                    .singleElement()
                    .extracting(record -> record.sessionId().value())
                    .isEqualTo(sessionId);
        }
    }
}
