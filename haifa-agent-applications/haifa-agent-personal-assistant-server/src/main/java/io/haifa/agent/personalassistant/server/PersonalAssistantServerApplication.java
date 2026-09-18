package io.haifa.agent.personalassistant.server;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.mission.MissionMaintenanceMain;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PersonalAssistantProperties.class)
public class PersonalAssistantServerApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "mission-maintenance".equals(args[0])) {
            MissionMaintenanceMain.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(PersonalAssistantServerApplication.class, args);
    }
}
