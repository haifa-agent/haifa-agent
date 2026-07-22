package io.haifa.agent.credential.api;

@FunctionalInterface
public interface CredentialHeaderSink {
    void put(String name, String value);
}
