package io.haifa.agent.credential.api;

@FunctionalInterface
public interface SecretFunction<T> {
    T apply(byte[] secret);
}
