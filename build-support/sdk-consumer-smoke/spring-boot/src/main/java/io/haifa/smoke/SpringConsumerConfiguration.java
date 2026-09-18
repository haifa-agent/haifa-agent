package io.haifa.smoke;

import io.haifa.agent.spring.autoconfigure.HaifaAgentStarterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringConsumerConfiguration {
    @Bean
    HaifaAgentStarterCustomizer trustedHostCustomizer() {
        return builder -> builder.instructions("Configured by an external Spring application.");
    }
}
