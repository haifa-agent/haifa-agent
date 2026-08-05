package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.haifa.agent.sdk.conversation.StartConversationCommand;
import org.junit.jupiter.api.Test;

class HaifaAgentStarterLiveIT {
    @Test
    void completesThePublishedQuickstartAgainstDeepSeek() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv("HAIFA_DEEPSEEK_LIVE_TEST")));
        assumeTrue(System.getenv(HaifaAgentStarterBuilder.API_KEY_ENVIRONMENT_VARIABLE) != null);

        try (var agent = HaifaAgentStarter.create()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "live-starter-1", "Starter live smoke", "Reply with one short greeting."));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());

            assertThat(completed.output()).isPresent();
        }
    }
}
