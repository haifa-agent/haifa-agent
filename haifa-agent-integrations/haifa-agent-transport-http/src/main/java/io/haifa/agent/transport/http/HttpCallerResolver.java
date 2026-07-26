package io.haifa.agent.transport.http;

@FunctionalInterface
public interface HttpCallerResolver {
    TrustedCallerContext resolve(HttpRequestMetadata request);
}
