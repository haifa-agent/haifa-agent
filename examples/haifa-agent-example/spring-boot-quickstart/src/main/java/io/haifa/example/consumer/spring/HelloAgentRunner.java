package io.haifa.example.consumer.spring;

import io.haifa.agent.sdk.api.HaifaAgent;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demonstrates constructor injection of the auto-configured Agent. */
@Component
@ConditionalOnProperty(name = "example.runner.enabled", havingValue = "true", matchIfMissing = false)
public final class HelloAgentRunner implements CommandLineRunner {
    private final HaifaAgent agent;

    public HelloAgentRunner(HaifaAgent agent) {
        this.agent = agent;
    }

    @Override
    public void run(String... arguments) throws Exception {
        var response = agent.chat("Use office_hours to report the Shanghai office schedule in one sentence.").await();
        System.out.println(response.text());
    }
}
