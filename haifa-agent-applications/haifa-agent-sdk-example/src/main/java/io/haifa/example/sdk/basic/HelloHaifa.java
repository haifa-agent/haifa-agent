package io.haifa.example.sdk.basic;

import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.starter.HaifaAgentStarter;

/** Minimal real-provider Quickstart; requires {@code DEEPSEEK_API_KEY}. */
public final class HelloHaifa {
    private HelloHaifa() {}

    public static void main(String[] arguments) throws Exception {
        try (HaifaAgent agent = HaifaAgentStarter.builder()
                .name("hello-haifa")
                .description("Minimal Haifa Agent quickstart")
                .build()) {
            var response =
                    agent.chat("Introduce Haifa Agent in one short sentence.").await();
            System.out.println(response.text());
        }
    }
}
