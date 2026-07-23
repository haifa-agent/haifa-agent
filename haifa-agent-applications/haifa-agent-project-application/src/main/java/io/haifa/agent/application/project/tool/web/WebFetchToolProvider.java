package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WebFetchToolProvider implements ToolProvider {
    private final WebFetchProvider provider;
    private final WebUrlPolicy urlPolicy;
    private final ToolProviderId id;

    public WebFetchToolProvider(WebFetchProvider provider, WebUrlPolicy urlPolicy) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy");
        if (!provider.descriptor().capabilities().fetch()) {
            throw new IllegalArgumentException("provider does not support fetch");
        }
        this.id = new ToolProviderId("web-fetch." + provider.descriptor().id().value());
    }

    @Override
    public ToolProviderId id() {
        return id;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        if (!request.binding().definition().name().value().equals("web.fetch")) {
            throw WebToolProviderSupport.invalid("web fetch provider received a different tool");
        }
        Map<String, Object> arguments = request.arguments().values();
        var decision = urlPolicy.evaluate(WebToolProviderSupport.uri(arguments, "url"));
        if (!decision.allowed()) {
            throw new ToolInvocationException(
                    WebFailureCode.WEB_URL_DENIED.name(),
                    ToolDispatchState.NOT_DISPATCHED,
                    "web URL was denied by policy: " + decision.denialCode().orElse("URL_DENIED"));
        }
        WebContentFormat preferred = WebToolProviderSupport.enumValue(
                arguments, "preferredFormat", WebContentFormat.class, WebContentFormat.MARKDOWN);
        int maxCharacters = arguments.get("maxCharacters") instanceof Number value ? value.intValue() : 200_000;
        try {
            var response = provider.fetch(
                    new WebFetchRequest(decision.normalizedUrl(), preferred, maxCharacters),
                    WebToolProviderSupport.context(request));
            var finalDecision = urlPolicy.evaluate(response.finalUrl());
            if (!finalDecision.allowed()) {
                throw new ToolInvocationException(
                        WebFailureCode.WEB_URL_DENIED.name(),
                        ToolDispatchState.ACKNOWLEDGED,
                        "provider returned a denied final URL");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestedUrl", response.requestedUrl().toString());
            data.put("finalUrl", finalDecision.normalizedUrl().toString());
            response.title().ifPresent(value -> data.put("title", value));
            data.put("content", response.content());
            data.put("format", response.format().name().toLowerCase(java.util.Locale.ROOT));
            data.put("mediaType", response.mediaType());
            response.charset().ifPresent(value -> data.put("charset", value));
            data.put("contentSha256", response.contentSha256());
            data.put("truncated", response.truncated());
            data.put("untrustedExternalContent", true);
            return new ToolResult(
                    true,
                    "Fetched untrusted external content through the configured web provider.",
                    Map.copyOf(data),
                    List.of(),
                    List.of(),
                    response.truncated());
        } catch (WebProviderException exception) {
            throw WebToolProviderSupport.map(exception);
        }
    }
}
