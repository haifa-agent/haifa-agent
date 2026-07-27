package io.haifa.agent.application.project.tool.web;

public interface WebSearchProvider {
    WebProviderDescriptor descriptor();

    WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context);
}
