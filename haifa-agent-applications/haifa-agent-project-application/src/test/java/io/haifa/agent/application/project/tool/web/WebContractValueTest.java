package io.haifa.agent.application.project.tool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebContractValueTest {
    @Test
    void normalizesProviderAndRequestValues() {
        var request = new WebSearchRequest(
                "  java agents ",
                5,
                Optional.of("en"),
                Optional.of("US"),
                Optional.of(WebFreshness.WEEK),
                List.of("Example.COM", "example.com"),
                List.of(),
                Optional.of(WebSafeSearch.MODERATE));

        assertThat(new WebProviderId("BRAVE").value()).isEqualTo("brave");
        assertThat(request.query()).isEqualTo("java agents");
        assertThat(request.includeDomains()).containsExactly("example.com");
    }

    @Test
    void rejectsAmbiguousOrUnboundedSearchInput() {
        assertThatThrownBy(() -> new WebSearchRequest(
                        "query",
                        21,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSearchRequest(
                        "query",
                        1,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of("example.com"),
                        List.of("example.com"),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseCollectionsAreImmutable() {
        var result = new WebSearchResult(
                1,
                "Title",
                URI.create("https://example.com"),
                "Snippet",
                Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
                Optional.of(0.8));
        var response = new WebSearchResponse("query", List.of(result), Optional.of("request-1"), false);

        assertThatThrownBy(() -> response.results().add(result)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void descriptorRequiresEndpointHostAndDeepCopiesConfiguration() {
        var configuration = new java.util.LinkedHashMap<String, String>();
        configuration.put("mode", "basic");
        var descriptor = new WebProviderDescriptor(
                new WebProviderId("tavily"),
                "Tavily",
                WebProviderCapabilities.searchOnly(Set.of(WebSearchOption.COUNTRY)),
                "tavily-search",
                "1.0.0",
                URI.create("https://api.tavily.com/search"),
                Set.of("api.tavily.com"),
                Optional.of(new CredentialRequirement(
                        new CredentialDefinitionId("web-search-tavily"),
                        "search",
                        Set.of("web.search"),
                        CredentialExposureMode.HTTP_HEADER)),
                configuration);
        configuration.put("mode", "advanced");

        assertThat(descriptor.configuration()).containsEntry("mode", "basic");
        assertThatThrownBy(() -> descriptor.configuration().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new WebProviderDescriptor(
                        new WebProviderId("bad"),
                        "Bad",
                        WebProviderCapabilities.fetchOnly(),
                        "bad",
                        "1.0.0",
                        URI.create("https://example.com/fetch"),
                        Set.of("other.example"),
                        Optional.empty(),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonHttpResultUrlsAndWildcardProviderHosts() {
        assertThatThrownBy(() -> new WebSearchResult(
                        1,
                        "Title",
                        URI.create("ftp://example.com/file"),
                        "Snippet",
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new WebProviderDescriptor(
                        new WebProviderId("test"),
                        "Test",
                        WebProviderCapabilities.searchOnly(Set.of()),
                        "test",
                        "1.0.0",
                        URI.create("https://example.com/search"),
                        Set.of("example.com", "*"),
                        Optional.empty(),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact hosts");
    }
}
