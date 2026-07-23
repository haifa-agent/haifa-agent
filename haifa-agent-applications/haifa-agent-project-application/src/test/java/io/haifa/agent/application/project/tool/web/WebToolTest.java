package io.haifa.agent.application.project.tool.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProviderId;
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
                .isInstanceOf(io.haifa.agent.application.project.tool.web.WebProviderException.class);
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
                        Instant.now().plusSeconds(30),
                        Optional.empty(),
                        (ToolCancellation) () -> false,
                        List.of()));

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("untrustedExternalContent", true)
                .containsEntry("content", "content");
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
    void rejectsOptionsUnsupportedByTheFrozenSearchProviderBeforeDispatch() {
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
                                Instant.now().plusSeconds(30),
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
        return new WebSearchProvider() {
            private final WebProviderDescriptor descriptor = WebToolTest.descriptor(id, true);

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

    private static WebProviderDescriptor descriptor(String id, boolean search) {
        URI endpoint = URI.create("https://" + id + ".example/api");
        return new WebProviderDescriptor(
                new WebProviderId(id),
                id,
                search ? WebProviderCapabilities.searchOnly(Set.of()) : WebProviderCapabilities.fetchOnly(),
                "test",
                "1.0.0",
                endpoint,
                Set.of(endpoint.getHost()),
                Optional.empty(),
                Map.of("mode", "test"));
    }
}
