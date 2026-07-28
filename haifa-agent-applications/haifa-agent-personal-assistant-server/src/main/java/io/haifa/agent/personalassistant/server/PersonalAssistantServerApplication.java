package io.haifa.agent.personalassistant.server;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PersonalAssistantProperties.class)
public class PersonalAssistantServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PersonalAssistantServerApplication.class, args);
    }
}
