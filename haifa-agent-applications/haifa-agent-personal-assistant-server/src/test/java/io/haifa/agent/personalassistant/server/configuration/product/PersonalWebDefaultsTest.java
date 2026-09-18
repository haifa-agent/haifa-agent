package io.haifa.agent.personalassistant.server.configuration.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class PersonalWebDefaultsTest {
    @Test
    void defaultApplicationConfigurationUsesTavilyForSearchAndFetch() throws Exception {
        var sources = new MutablePropertySources();
        var resource = new ClassPathResource("application.yml");
        for (var source : new YamlPropertySourceLoader().load("application", resource)) {
            sources.addLast(source);
        }

        var web = new Binder(
                        ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("haifa.personal.web", Bindable.of(PersonalAssistantProperties.Web.class))
                .orElseThrow(() -> new AssertionError("default Web configuration did not bind"));

        assertThat(web.search().providerId()).isEqualTo("tavily");
        assertThat(web.search().endpoint()).isEqualTo(URI.create("https://api.tavily.com/search"));
        assertThat(web.search().credentialReference()).isEqualTo("env://TAVILY_API_KEY");
        assertThat(web.fetch().providerId()).isEqualTo("tavily");
        assertThat(web.fetch().endpoint()).isEqualTo(URI.create("https://api.tavily.com/extract"));
        assertThat(web.fetch().credentialReference()).isEqualTo("env://TAVILY_API_KEY");
    }
}
