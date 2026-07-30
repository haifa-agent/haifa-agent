package io.haifa.agent.web;

public interface WebSearchProvider {
    WebProviderDescriptor descriptor();

    WebSearchResponse search(WebSearchRequest request, WebProviderInvocationContext context);
}
