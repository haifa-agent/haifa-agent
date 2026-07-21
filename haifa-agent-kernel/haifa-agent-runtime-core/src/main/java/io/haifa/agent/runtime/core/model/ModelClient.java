package io.haifa.agent.runtime.core.model;

@FunctionalInterface
public interface ModelClient {
    ModelResponse invoke(ModelRequest request);
}
