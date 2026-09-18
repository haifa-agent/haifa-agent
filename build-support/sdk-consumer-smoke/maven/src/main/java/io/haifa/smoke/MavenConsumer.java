package io.haifa.smoke;

import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.starter.HaifaAgentStarter;

public final class MavenConsumer {
    private MavenConsumer() {}

    public static HaifaAgent createFromDocumentedEntryPoint() {
        return HaifaAgentStarter.builder().build();
    }
}
