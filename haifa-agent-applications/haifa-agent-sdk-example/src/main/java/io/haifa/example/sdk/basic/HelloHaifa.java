package io.haifa.example.sdk.basic;

import io.haifa.agent.starter.HaifaAgentStarter;

/** Minimal real-provider Quickstart; requires {@code DEEPSEEK_API_KEY}. */
public final class HelloHaifa {
    private HelloHaifa() {}

    public static void main(String[] arguments) throws Exception {
        try (var haifa = HaifaAgentStarter.create()) {
            System.out.println(haifa.chat("Hello, Java!").await().text());
        }
    }
}
