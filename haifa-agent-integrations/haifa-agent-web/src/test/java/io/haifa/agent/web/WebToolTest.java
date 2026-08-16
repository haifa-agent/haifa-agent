package io.haifa.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebToolTest {
    @Test
    void registriesResolveExactlyAndRejectDuplicates() {
        WebSearchProvider brave = searchProvider("brave");
        var registry = new WebSearchProviderRegistry(List.of(brave));

        assertThat(registry.require(new WebProviderId("brave"))).isSameAs(brave);
        assertThatThrownBy(() -> registry.require(new WebProviderId("missing")))
                .isInstanceOf(io.haifa.agent.web.WebProviderException.class);
        assertThatThrownBy(() -> new WebSearchProviderRegistry(List.of(brave, searchProvider("brave"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void URLPolicyAllowsPublicHttpsAndDeniesLocalTargets() {
        var policy = new DefaultWebUrlPolicy();

        assertThat(policy.evaluate(URI.create("https://Example.COM/path#fragment"))
                        .normalizedUrl())
                .isEqualTo(URI.create("https://example.com/path"));
        assertThat(policy.evaluate(URI.create("https://例子.测试/path")).normalizedUrl())
                .isEqualTo(URI.create("https://xn--fsqu00a.xn--0zwm56d/path"));
        assertThat(policy.evaluate(URI.create("https://[2606:4700:4700::1111]/dns"))
                        .allowed())
                .isTrue();
        assertThat(policy.evaluate(URI.create("http://127.0.0.1/admin")).allowed())
                .isFalse();
        assertThat(policy.evaluate(URI.create("http://2130706433/admin")).allowed())
                .isFalse();
        assertThat(policy.evaluate(URI.create("http://169.254.169.254/latest/meta-data"))
                        .allowed())
                .isFalse();
        assertThat(policy.evaluate(URI.create("http://[::ffff:169.254.169.254]/latest/meta-data"))
                        .allowed())
                .isFalse();
        assertThat(policy.evaluate(URI.create("http://[fd00::1]/internal")).allowed())
                .isFalse();
        assertThat(policy.evaluate(URI.create("https://localhost/value")).allowed())
                .isFalse();
        assertThat(policy.evaluate(URI.create("ftp://example.com/file")).allowed())
                .isFalse();
    }

    @Test
    void contributionsFreezeConcreteProviderIdentityAndFixedHosts() {
        var contribution = new WebToolCatalog().search(searchProvider("brave"));
        var catalog = new ToolCatalogBuilder()
                .register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider())
                .freeze();
        var binding = catalog.snapshot().bindings().getFirst();

        assertThat(binding.alias().value()).isEqualTo("web_search");
        assertThat(binding.coordinate().providerId()).isEqualTo(new ToolProviderId("web-search.brave"));
        assertThat(binding.definition().resources().networkHosts()).containsExactly("brave.example");
        assertThat(binding.providerBindingReference()).startsWith("web:search:brave:sha256:");
    }

    @Test
    void searchSchemaExposesOnlyOptionsSupportedByTheFrozenProvider() {
        var catalog = new WebToolCatalog();
        var aliyun = catalog.search(searchProvider(
                "aliyun",
                Set.of(WebSearchOption.FRESHNESS, WebSearchOption.INCLUDE_DOMAINS, WebSearchOption.EXCLUDE_DOMAINS)));
        var brave = catalog.search(searchProvider(
                "brave",
                Set.of(
                        WebSearchOption.LANGUAGE,
                        WebSearchOption.COUNTRY,
                        WebSearchOption.FRESHNESS,
                        WebSearchOption.SAFE_SEARCH)));
        var tavily = catalog.search(searchProvider(
                "tavily",
                Set.of(
                        WebSearchOption.COUNTRY,
                        WebSearchOption.FRESHNESS,
                        WebSearchOption.INCLUDE_DOMAINS,
                        WebSearchOption.EXCLUDE_DOMAINS)));

        assertThat(inputProperties(aliyun))
                .containsOnlyKeys("query", "maxResults", "freshness", "includeDomains", "excludeDomains");
        assertThat(inputProperties(brave))
                .containsOnlyKeys("query", "maxResults", "language", "country", "freshness", "safeSearch");
        assertThat(inputProperties(tavily))
                .containsOnlyKeys("query", "maxResults", "country", "freshness", "includeDomains", "excludeDomains");
        Map<?, ?> countrySchema = (Map<?, ?>) inputProperties(tavily).get("country");
        assertThat(countrySchema.get("minLength")).isEqualTo(2);
        assertThat(countrySchema.get("maxLength")).isEqualTo(2);
        assertThat(countrySchema.get("description")).isEqualTo("ISO 3166-1 alpha-2 country code");
    }

    @Test
    void searchCountryUsesLowercaseIsoAlpha2Codes() {
        var request = new WebSearchRequest(
                "agent",
                3,
                Optional.empty(),
                Optional.of("CN"),
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty());

        assertThat(request.country()).contains("cn");
        assertThatThrownBy(() -> new WebSearchRequest(
                        "agent",
                        3,
                        Optional.empty(),
                        Optional.of("china"),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 3166-1 alpha-2");
    }

    @Test
    void searchBindingIdentityChangesWhenProviderCapabilitiesChange() {
        var catalog = new WebToolCatalog();
        var language = catalog.search(searchProvider("same", Set.of(WebSearchOption.LANGUAGE)));
        var country = catalog.search(searchProvider("same", Set.of(WebSearchOption.COUNTRY)));

        assertThat(language.providerBindingReference()).isNotEqualTo(country.providerBindingReference());
        assertThat(inputProperties(language)).containsKey("language").doesNotContainKey("country");
        assertThat(inputProperties(country)).containsKey("country").doesNotContainKey("language");
    }

    @Test
    void fetchToolReturnsStructuredUntrustedContent() {
        var contribution = new WebToolCatalog().fetch(fetchProvider(), new DefaultWebUrlPolicy());
        var catalog = new ToolCatalogBuilder()
                .register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider())
                .freeze();
        var binding = catalog.snapshot().bindings().getFirst();
        var result = contribution
                .provider()
                .invoke(new ToolInvocationRequest(
                        binding,
                        new ToolCallId("call-1"),
                        new AgentRunId("run-1"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("user-1", "user"),
                        new ToolArguments(
                                binding.definition().inputSchema().id(),
                                binding.definition().inputSchema().version(),
                                Map.of("url", "https://example.com/page", "maxCharacters", 100)),
                        Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(30),
                        Optional.empty(),
                        (ToolCancellation) () -> false,
                        List.of()));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("untrustedExternalContent", true)
                .containsEntry("content", "content");
    }

    @Test
    void fetchToolUsesAContextSafeDefaultWhenMaximumCharactersIsOmitted() {
        int[] observedMaximum = new int[1];
        WebFetchProvider provider = new WebFetchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("aliyun", false);
            }

            @Override
            public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
                observedMaximum[0] = request.maxCharacters();
                context.observer().dispatched();
                context.observer().acknowledged();
                return new WebFetchResponse(
                        request.url(),
                        request.url(),
                        Optional.empty(),
                        "content",
                        WebContentFormat.TEXT,
                        "text/plain",
                        Optional.empty(),
                        "ed7002b439e9ac845f22357d822bac1444737f02b59d2f9cd2b25c44b7b0d808",
                        false);
            }
        };
        var contribution = new WebToolCatalog().fetch(provider, new DefaultWebUrlPolicy());
        var catalog = new ToolCatalogBuilder()
                .register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider())
                .freeze();
        var binding = catalog.snapshot().bindings().getFirst();

        contribution
                .provider()
                .invoke(new ToolInvocationRequest(
                        binding,
                        new ToolCallId("call-default-limit"),
                        new AgentRunId("run-default-limit"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("user-1", "user"),
                        new ToolArguments(
                                binding.definition().inputSchema().id(),
                                binding.definition().inputSchema().version(),
                                Map.of("url", "https://example.com/page")),
                        Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(30),
                        Optional.empty(),
                        (ToolCancellation) () -> false,
                        List.of()));

        assertThat(observedMaximum[0]).isEqualTo(WebFetchToolProvider.DEFAULT_MAX_CHARACTERS);
        assertThat(((Map<?, ?>) inputProperties(contribution).get("maxCharacters")).get("default"))
                .isEqualTo(WebFetchToolProvider.DEFAULT_MAX_CHARACTERS);
    }

    @Test
    void frozenFetchBindingIncludesUrlPolicyConfiguration() {
        var catalog = new WebToolCatalog();
        var defaultBinding =
                catalog.fetch(fetchProvider(), new DefaultWebUrlPolicy()).providerBindingReference();
        var restrictedBinding = catalog.fetch(fetchProvider(), new DefaultWebUrlPolicy(Set.of("example.com")))
                .providerBindingReference();

        assertThat(defaultBinding).startsWith("web:fetch:aliyun:sha256:");
        assertThat(restrictedBinding).startsWith("web:fetch:aliyun:sha256:");
        assertThat(restrictedBinding).isNotEqualTo(defaultBinding);
    }

    @Test
    void unavailableFetchSourceReturnsASuccessfulNegativeResult() {
        WebFetchProvider unavailable = new WebFetchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("aliyun", false);
            }

            @Override
            public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
                context.observer().dispatched();
                context.observer().acknowledged();
                throw new WebProviderException(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "source returned no usable content");
            }
        };
        var invocation = fetchInvocation(
                new WebToolCatalog().fetch(unavailable, new DefaultWebUrlPolicy()), "https://example.com/unavailable");

        ToolResult result = invocation.contribution().provider().invoke(invocation.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("failureCode", "WEB_PROVIDER_RESPONSE_INVALID")
                .containsEntry("retryWithAnotherSource", true);
        assertThat(new JsonSchema202012Validator()
                        .validate(invocation.contribution().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();
    }

    @Test
    void fetchAuthenticationFailureLeavesTheSourceUnavailableWithoutAbortingResearch() {
        WebFetchProvider unavailable = new WebFetchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("aliyun", false);
            }

            @Override
            public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
                throw new WebProviderException(
                        WebFailureCode.WEB_AUTH_FAILED, WebDispatchState.ACKNOWLEDGED, "credential rejected");
            }
        };
        var invocation = fetchInvocation(
                new WebToolCatalog().fetch(unavailable, new DefaultWebUrlPolicy()), "https://example.com/private");

        ToolResult result = invocation.contribution().provider().invoke(invocation.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("failureCode", "WEB_AUTH_FAILED")
                .containsEntry("sourceAvailable", false)
                .containsEntry("retryWithAnotherSource", true);
    }

    @Test
    void readOnlyFetchOutcomeUnknownReturnsASuccessfulNegativeResult() {
        WebFetchProvider unavailable = new WebFetchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("aliyun", false);
            }

            @Override
            public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
                context.observer().dispatched();
                throw new WebProviderException(
                        WebFailureCode.WEB_PROVIDER_FAILED,
                        WebDispatchState.OUTCOME_UNKNOWN,
                        "transport outcome is unknown");
            }
        };
        var invocation = fetchInvocation(
                new WebToolCatalog().fetch(unavailable, new DefaultWebUrlPolicy()), "https://example.com/unknown");

        ToolResult result = invocation.contribution().provider().invoke(invocation.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("failureCode", "WEB_PROVIDER_FAILED")
                .containsEntry("retryWithAnotherSource", true);
        assertThat(new JsonSchema202012Validator()
                        .validate(invocation.contribution().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();
    }

    @Test
    void unusableSearchResponseReturnsASuccessfulNegativeResult() {
        WebSearchProvider unavailable = new WebSearchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("aliyun", true);
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context) {
                context.observer().dispatched();
                context.observer().acknowledged();
                throw new WebProviderException(
                        WebFailureCode.WEB_PROVIDER_RESPONSE_INVALID,
                        WebDispatchState.ACKNOWLEDGED,
                        "provider returned an unusable result");
            }
        };
        var invocation = searchInvocation(new WebToolCatalog().search(unavailable), "jingning hydropower");

        ToolResult result = invocation.contribution().provider().invoke(invocation.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("failureCode", "WEB_PROVIDER_RESPONSE_INVALID")
                .containsEntry("retryWithRefinedQuery", true);
        assertThat(new JsonSchema202012Validator()
                        .validate(invocation.contribution().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();
    }

    @Test
    void providerRejectedSearchRequestReturnsASuccessfulNegativeResult() {
        WebSearchProvider unavailable = new WebSearchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("tavily", true);
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context) {
                context.observer().dispatched();
                context.observer().acknowledged();
                throw new WebProviderException(
                        WebFailureCode.WEB_INVALID_REQUEST, WebDispatchState.ACKNOWLEDGED, "provider rejected request");
            }
        };
        var invocation = searchInvocation(new WebToolCatalog().search(unavailable), "hangzhou news");

        ToolResult result = invocation.contribution().provider().invoke(invocation.request());

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("failureCode", "WEB_INVALID_REQUEST")
                .containsEntry("searchResultsAvailable", false)
                .containsEntry("retryWithRefinedQuery", true);
        assertThat(new JsonSchema202012Validator()
                        .validate(invocation.contribution().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();
    }

    @Test
    void searchAuthenticationFailureRemainsAnInfrastructureFailure() {
        WebSearchProvider unavailable = new WebSearchProvider() {
            @Override
            public WebProviderDescriptor descriptor() {
                return WebToolTest.descriptor("aliyun", true);
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context) {
                throw new WebProviderException(
                        WebFailureCode.WEB_AUTH_FAILED, WebDispatchState.ACKNOWLEDGED, "credential rejected");
            }
        };
        var invocation = searchInvocation(new WebToolCatalog().search(unavailable), "jingning hydropower");

        assertThatThrownBy(() -> invocation.contribution().provider().invoke(invocation.request()))
                .isInstanceOfSatisfying(ToolInvocationException.class, failure -> assertThat(failure.failureCode())
                        .isEqualTo("WEB_AUTH_FAILED"));
    }

    @Test
    void providerAdapterRejectsUnsupportedOptionsBeforeDispatchAsDefenseInDepth() {
        var contribution = new WebToolCatalog().search(searchProvider("aliyun"));
        var catalog = new ToolCatalogBuilder()
                .register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider())
                .freeze();
        var binding = catalog.snapshot().bindings().getFirst();

        assertThatThrownBy(() -> contribution
                        .provider()
                        .invoke(new ToolInvocationRequest(
                                binding,
                                new ToolCallId("call-unsupported"),
                                new AgentRunId("run-unsupported"),
                                new TenantRef("tenant-1"),
                                new PrincipalRef("user-1", "user"),
                                new ToolArguments(
                                        binding.definition().inputSchema().id(),
                                        binding.definition().inputSchema().version(),
                                        Map.of("query", "agent", "language", "en")),
                                Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(30),
                                Optional.empty(),
                                (ToolCancellation) () -> false,
                                List.of())))
                .isInstanceOfSatisfying(ToolInvocationException.class, exception -> {
                    assertThat(exception.failureCode()).isEqualTo("WEB_UNSUPPORTED_OPTION");
                    assertThat(exception.dispatchState())
                            .isEqualTo(io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED);
                });
    }

    private static WebSearchProvider searchProvider(String id) {
        return searchProvider(id, Set.of());
    }

    private static WebSearchProvider searchProvider(String id, Set<WebSearchOption> supportedOptions) {
        return new WebSearchProvider() {
            private final WebProviderDescriptor descriptor = WebToolTest.descriptor(id, supportedOptions);

            @Override
            public WebProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context) {
                context.observer().dispatched();
                context.observer().acknowledged();
                return new WebSearchResponse(
                        request.query(),
                        List.of(new WebSearchResult(
                                1,
                                "Result",
                                URI.create("https://example.com"),
                                "snippet",
                                Optional.empty(),
                                Optional.empty())),
                        Optional.empty(),
                        false);
            }
        };
    }

    private static WebFetchProvider fetchProvider() {
        return new WebFetchProvider() {
            private final WebProviderDescriptor descriptor = WebToolTest.descriptor("aliyun", false);

            @Override
            public WebProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context) {
                context.observer().dispatched();
                context.observer().acknowledged();
                return new WebFetchResponse(
                        request.url(),
                        request.url(),
                        Optional.of("Title"),
                        "content",
                        WebContentFormat.TEXT,
                        "text/plain",
                        Optional.of("UTF-8"),
                        "ed7002b439e9ac845f22357d822bac1444737f02b59d2f9cd2b25c44b7b0d808",
                        false);
            }
        };
    }

    private static FetchInvocation fetchInvocation(WebToolCatalogContribution contribution, String url) {
        var catalog = new ToolCatalogBuilder()
                .register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider())
                .freeze();
        var binding = catalog.snapshot().bindings().getFirst();
        return new FetchInvocation(
                contribution,
                new ToolInvocationRequest(
                        binding,
                        new ToolCallId("call-fetch"),
                        new AgentRunId("run-fetch"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("user-1", "user"),
                        new ToolArguments(
                                binding.definition().inputSchema().id(),
                                binding.definition().inputSchema().version(),
                                Map.of("url", url, "maxCharacters", 100)),
                        Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(30),
                        Optional.empty(),
                        (ToolCancellation) () -> false,
                        List.of()));
    }

    private static SearchInvocation searchInvocation(WebToolCatalogContribution contribution, String query) {
        var catalog = new ToolCatalogBuilder()
                .register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider())
                .freeze();
        var binding = catalog.snapshot().bindings().getFirst();
        return new SearchInvocation(
                contribution,
                new ToolInvocationRequest(
                        binding,
                        new ToolCallId("call-search"),
                        new AgentRunId("run-search"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("user-1", "user"),
                        new ToolArguments(
                                binding.definition().inputSchema().id(),
                                binding.definition().inputSchema().version(),
                                Map.of("query", query)),
                        Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(30),
                        Optional.empty(),
                        (ToolCancellation) () -> false,
                        List.of()));
    }

    private record FetchInvocation(WebToolCatalogContribution contribution, ToolInvocationRequest request) {}

    private record SearchInvocation(WebToolCatalogContribution contribution, ToolInvocationRequest request) {}

    private static WebProviderDescriptor descriptor(String id, boolean search) {
        return descriptor(id, search ? Set.of() : null);
    }

    private static WebProviderDescriptor descriptor(String id, Set<WebSearchOption> supportedOptions) {
        URI endpoint = URI.create("https://" + id + ".example/api");
        return new WebProviderDescriptor(
                new WebProviderId(id),
                id,
                supportedOptions == null
                        ? WebProviderCapabilities.fetchOnly()
                        : WebProviderCapabilities.searchOnly(supportedOptions),
                "test",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost()),
                Optional.empty(),
                Map.of("mode", "test"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputProperties(WebToolCatalogContribution contribution) {
        return (Map<String, Object>)
                contribution.definition().inputSchema().document().get("properties");
    }
}
