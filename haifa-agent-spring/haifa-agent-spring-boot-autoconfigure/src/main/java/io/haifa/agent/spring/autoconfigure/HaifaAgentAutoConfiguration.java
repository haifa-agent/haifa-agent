package io.haifa.agent.spring.autoconfigure;

import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.starter.HaifaAgentStarter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configures one process-local Agent from the pure Java safe-default Starter. */
@AutoConfiguration
@ConditionalOnClass({HaifaAgent.class, HaifaAgentStarter.class})
@ConditionalOnProperty(prefix = "haifa.agent", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(HaifaAgentProperties.class)
public class HaifaAgentAutoConfiguration {
    /** Creates the Spring Boot auto-configuration. */
    public HaifaAgentAutoConfiguration() {}

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(HaifaAgent.class)
    HaifaAgent haifaAgent(
            HaifaAgentProperties properties,
            ObjectProvider<JavaTool<?, ?>> tools,
            ObjectProvider<SdkCallerProvider> callerProviders,
            ObjectProvider<HaifaAgentStarterCustomizer> customizers) {
        String environmentVariable = properties.getModel().getCredentialEnvironmentVariable();
        try {
            var builder = HaifaAgentStarter.builder()
                    .credentialEnvironmentVariable(environmentVariable)
                    .connectTimeout(properties.getModel().getConnectTimeout())
                    .callerProvider(callerProviders.getIfAvailable(SdkCallerProvider::defaultPublicUser));
            String instructions = properties.getInstructions();
            if (instructions != null) {
                builder.instructions(instructions);
            }
            customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
            tools.orderedStream().forEach(builder::tool);
            return builder.build();
        } catch (RuntimeException exception) {
            throw new HaifaAgentAutoConfigurationException(safeEnvironmentVariable(environmentVariable), exception);
        }
    }

    private static String safeEnvironmentVariable(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*") ? value : "configured credential";
    }
}
