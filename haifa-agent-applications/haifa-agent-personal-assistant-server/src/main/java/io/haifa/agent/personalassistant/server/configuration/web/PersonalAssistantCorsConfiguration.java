package io.haifa.agent.personalassistant.server.configuration.web;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** Minimal browser boundary for the independently served loopback Web application. */
@Configuration(proxyBeanMethods = false)
public class PersonalAssistantCorsConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    CorsWebFilter personalAssistantCorsWebFilter() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(
                List.of("http://127.0.0.1:20000", "http://localhost:20000", "http://[::1]:20000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of("Accept", "Content-Type", "X-Haifa-CSRF", "Idempotency-Key", "If-Match", "Last-Event-ID"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/v1/admin/**", configuration);
        return new CorsWebFilter(source);
    }
}
