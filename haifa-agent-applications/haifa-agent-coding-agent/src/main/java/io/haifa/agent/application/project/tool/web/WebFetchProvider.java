package io.haifa.agent.application.project.tool.web;

public interface WebFetchProvider {
    WebProviderDescriptor descriptor();

    WebFetchResponse fetch(WebFetchRequest request, WebProviderInvocationContext context);
}
