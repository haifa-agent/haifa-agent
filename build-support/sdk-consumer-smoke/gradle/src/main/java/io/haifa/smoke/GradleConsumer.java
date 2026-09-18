package io.haifa.smoke;

import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.starter.HaifaAgentStarter;

public final class GradleConsumer {
    private GradleConsumer() {}

    public static HaifaAgent createFromDocumentedEntryPoint() {
        return HaifaAgentStarter.builder().build();
    }
}
