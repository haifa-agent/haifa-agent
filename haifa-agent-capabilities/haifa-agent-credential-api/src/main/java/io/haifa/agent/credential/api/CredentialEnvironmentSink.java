package io.haifa.agent.credential.api;

@FunctionalInterface
public interface CredentialEnvironmentSink {
    void put(String name, String value);
}
