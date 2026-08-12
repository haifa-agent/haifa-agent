package io.haifa.agent.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class HaifaAgentAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HaifaAgentAutoConfiguration.class))
            .withPropertyValues("haifa.agent.model.credential-environment-variable=PATH");

    @Test
    void createsSafeDefaultAgentAndClosesItWithTheApplicationContext() {
        AtomicReference<HaifaAgent> created = new AtomicReference<>();

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(HaifaAgent.class);
            var agent = context.getBean(HaifaAgent.class);
            created.set(agent);
            assertThat(agent.assembly().profile().runProfileId()).isEqualTo("deepseek-v4-flash");
        });

        assertThatThrownBy(() -> created.get().runs())
                .isInstanceOf(HaifaAgentException.class)
                .hasMessageContaining("AGENT_CLOSED");
    }

    @Test
    void collectsJavaToolBeansAndBindsSafeProperties() {
        contextRunner
                .withBean(WeatherTool.class)
                .withPropertyValues(
                        "haifa.agent.instructions=Answer with verified weather only.",
                        "haifa.agent.name=spring-weather-agent",
                        "haifa.agent.description=Spring display metadata",
                        "haifa.agent.model.credential-environment-variable=PATH",
                        "haifa.agent.model.connect-timeout=3s")
                .run(context -> {
                    assertThat(context).hasSingleBean(HaifaAgent.class);
                    var agent = context.getBean(HaifaAgent.class);
                    assertThat(agent.assembly().profile().instructions())
                            .isEqualTo("Answer with verified weather only.");
                    assertThat(agent.assembly().profile().allowedTools()).containsExactly("weather_get");
                    assertThat(agent.metadata().name()).isEqualTo("spring-weather-agent");
                    assertThat(agent.metadata().description()).isEqualTo("Spring display metadata");

                    var properties = context.getBean(HaifaAgentProperties.class);
                    assertThat(properties.getModel().getCredentialEnvironmentVariable())
                            .isEqualTo("PATH");
                    assertThat(properties.getModel().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getName()).isEqualTo("spring-weather-agent");
                    assertThat(properties.getDescription()).isEqualTo("Spring display metadata");
                });
    }

    @Test
    void appliesOrderedStarterCustomizersBeforeBuildingTheAgent() {
        contextRunner
                .withBean(
                        HaifaAgentStarterCustomizer.class,
                        () -> builder -> builder.instructions("Customized by the trusted Spring host."))
                .run(context -> {
                    assertThat(context).hasSingleBean(HaifaAgent.class);
                    assertThat(context.getBean(HaifaAgent.class)
                                    .assembly()
                                    .profile()
                                    .instructions())
                            .isEqualTo("Customized by the trusted Spring host.");
                });
    }

    @Test
    void backsOffWhenTheApplicationProvidesAnAgent() {
        try (var customAgent = HaifaAgentStarter.builder()
                .credentialEnvironmentVariable("PATH")
                .instructions("Application-owned Agent")
                .build()) {
            contextRunner.withBean(HaifaAgent.class, () -> customAgent).run(context -> {
                assertThat(context).hasSingleBean(HaifaAgent.class);
                assertThat(context.getBean(HaifaAgent.class)).isSameAs(customAgent);
            });
        }
    }

    @Test
    void canDisableTheIntegrationWithoutAProviderCredential() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HaifaAgentAutoConfiguration.class))
                .withPropertyValues("haifa.agent.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HaifaAgent.class));
    }

    @Test
    void failsSafelyWhenTheConfiguredCredentialIsMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HaifaAgentAutoConfiguration.class))
                .withPropertyValues("haifa.agent.model.credential-environment-variable=HAIFA_TEST_MISSING_DEEPSEEK_KEY")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Haifa Agent auto-configuration failed")
                            .hasStackTraceContaining("HAIFA_TEST_MISSING_DEEPSEEK_KEY is not configured");
                });
    }

    @Test
    void publishesAutoConfigurationAndPropertyMetadata() {
        ClassLoader loader = HaifaAgentAutoConfiguration.class.getClassLoader();

        assertThat(loader.getResource(
                        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
                .isNotNull();
        assertThat(loader.getResource("META-INF/spring-configuration-metadata.json"))
                .isNotNull();
    }

    @Test
    void failureAnalysisNamesOnlyTheCredentialVariable() {
        var failure = new HaifaAgentAutoConfigurationException(
                "MY_DEEPSEEK_KEY", new IllegalStateException("provider detail"));

        var analysis = new HaifaAgentFailureAnalyzer().analyze(failure);

        assertThat(analysis.getDescription()).contains("Credential values were not logged");
        assertThat(analysis.getAction()).contains("MY_DEEPSEEK_KEY").doesNotContain("provider detail");
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC = JavaToolSpec.builder(
                        "weather.get", WeatherRequest.class, WeatherResponse.class)
                .alias("weather_get")
                .pure()
                .build();

        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return SPEC;
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse("sunny");
        }
    }
}
