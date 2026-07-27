package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class WebToolCatalog {
    public WebToolCatalogContribution search(WebSearchProvider provider) {
        var adapter = new WebSearchToolProvider(provider);
        String binding = bindingReference("search", provider.descriptor(), Map.of());
        return new WebToolCatalogContribution(
                new ToolAlias("web_search"),
                searchDefinition(adapter, provider.descriptor(), binding),
                binding,
                adapter);
    }

    public WebToolCatalogContribution fetch(WebFetchProvider provider, WebUrlPolicy urlPolicy) {
        var adapter = new WebFetchToolProvider(provider, urlPolicy);
        Map<String, String> policy = new TreeMap<>();
        policy.put("urlPolicyId", urlPolicy.policyId());
        policy.put("urlPolicyVersion", urlPolicy.policyVersion());
        urlPolicy.configuration().forEach((key, value) -> policy.put("urlPolicy." + key, value));
        String binding = bindingReference("fetch", provider.descriptor(), policy);
        return new WebToolCatalogContribution(
                new ToolAlias("web_fetch"), fetchDefinition(adapter, provider.descriptor(), binding), binding, adapter);
    }

    private static ToolDefinition searchDefinition(
            WebSearchToolProvider provider, WebProviderDescriptor descriptor, String binding) {
        return definition(
                "web.search",
                provider.id(),
                "Search the public web",
                "Search public web sources and return structured, untrusted external results.",
                searchInputSchema(),
                searchOutputSchema(),
                descriptor,
                binding,
                Set.of("web", "search"));
    }

    private static ToolDefinition fetchDefinition(
            WebFetchToolProvider provider, WebProviderDescriptor descriptor, String binding) {
        return definition(
                "web.fetch",
                provider.id(),
                "Fetch a public web page",
                "Fetch one public URL through the configured provider and return untrusted external content.",
                fetchInputSchema(),
                fetchOutputSchema(),
                descriptor,
                binding,
                Set.of("web", "fetch"));
    }

    private static ToolDefinition definition(
            String name,
            io.haifa.agent.tool.api.ToolProviderId providerId,
            String title,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            WebProviderDescriptor descriptor,
            String binding,
            Set<String> tags) {
        List<CredentialRequirement> credentials =
                descriptor.credentialRequirement().map(List::of).orElseGet(List::of);
        Set<ToolSideEffect> sideEffects = credentials.isEmpty()
                ? Set.of(ToolSideEffect.NETWORK_ACCESS)
                : Set.of(ToolSideEffect.NETWORK_ACCESS, ToolSideEffect.CREDENTIAL_USE);
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                providerId,
                title,
                description,
                new ToolSchema("haifa." + name + ".input", "1.0.0", inputSchema),
                new ToolSchema("haifa." + name + ".output", "1.0.0", outputSchema),
                ToolExecutionMode.REMOTE_PROVIDER,
                true,
                Duration.ofSeconds(30),
                "per-principal-web-read",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.MEDIUM,
                sideEffects,
                new ToolResourceRequirements(Set.of(), descriptor.networkHosts(), Set.of()),
                credentials,
                ToolApprovalRequirement.POLICY,
                "haifa-web:" + binding,
                false,
                tags);
    }

    private static String bindingReference(
            String operation, WebProviderDescriptor descriptor, Map<String, String> additionalConfiguration) {
        Map<String, String> values = new TreeMap<>(descriptor.configuration());
        values.putAll(additionalConfiguration);
        values.put("adapterKind", descriptor.adapterKind());
        values.put("adapterVersion", descriptor.adapterVersion());
        values.put("endpoint", descriptor.endpoint().toString());
        values.put("operation", operation);
        values.put("providerId", descriptor.id().value());
        String canonical = values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left + "\n" + right);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "web:" + operation + ":" + descriptor.id().value() + ":sha256:"
                    + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, Object> searchInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 2048));
        properties.put("maxResults", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        properties.put("language", Map.of("type", "string", "minLength", 1, "maxLength", 32));
        properties.put("country", Map.of("type", "string", "minLength", 1, "maxLength", 64));
        properties.put("freshness", Map.of("type", "string", "enum", List.of("day", "week", "month", "year")));
        properties.put("includeDomains", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 50));
        properties.put("excludeDomains", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 50));
        properties.put("safeSearch", Map.of("type", "string", "enum", List.of("off", "moderate", "strict")));
        return objectSchema(properties, List.of("query"));
    }

    private static Map<String, Object> searchOutputSchema() {
        Map<String, Object> resultProperties = new LinkedHashMap<>();
        resultProperties.put("rank", Map.of("type", "integer", "minimum", 1));
        resultProperties.put("title", Map.of("type", "string"));
        resultProperties.put("url", Map.of("type", "string"));
        resultProperties.put("snippet", Map.of("type", "string"));
        resultProperties.put("publishedAt", Map.of("type", "string"));
        resultProperties.put("score", Map.of("type", "number"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string"));
        properties.put(
                "results",
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        20,
                        "items",
                        objectSchema(resultProperties, List.of("rank", "title", "url", "snippet"))));
        properties.put("truncated", Map.of("type", "boolean"));
        properties.put("untrustedExternalContent", Map.of("type", "boolean", "const", true));
        return objectSchema(properties, List.of("query", "results", "truncated", "untrustedExternalContent"));
    }

    private static Map<String, Object> fetchInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of("type", "string", "minLength", 1, "maxLength", 4096));
        properties.put("preferredFormat", Map.of("type", "string", "enum", List.of("markdown", "text")));
        properties.put("maxCharacters", Map.of("type", "integer", "minimum", 1, "maximum", 1_000_000));
        return objectSchema(properties, List.of("url"));
    }

    private static Map<String, Object> fetchOutputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requestedUrl", Map.of("type", "string"));
        properties.put("finalUrl", Map.of("type", "string"));
        properties.put("title", Map.of("type", "string"));
        properties.put("content", Map.of("type", "string"));
        properties.put("format", Map.of("type", "string", "enum", List.of("markdown", "text", "html")));
        properties.put("mediaType", Map.of("type", "string"));
        properties.put("charset", Map.of("type", "string"));
        properties.put("contentSha256", Map.of("type", "string", "minLength", 64, "maxLength", 64));
        properties.put("truncated", Map.of("type", "boolean"));
        properties.put("untrustedExternalContent", Map.of("type", "boolean", "const", true));
        return objectSchema(
                properties,
                List.of(
                        "requestedUrl",
                        "finalUrl",
                        "content",
                        "format",
                        "mediaType",
                        "contentSha256",
                        "truncated",
                        "untrustedExternalContent"));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", ToolSchema.DRAFT_2020_12);
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        schema.put("required", List.copyOf(new ArrayList<>(required)));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
