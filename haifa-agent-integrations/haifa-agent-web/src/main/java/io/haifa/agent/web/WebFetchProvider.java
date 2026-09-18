package io.haifa.agent.web;

public interface WebFetchProvider {
    WebProviderDescriptor descriptor();

    WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context);
}
