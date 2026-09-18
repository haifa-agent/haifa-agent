package io.haifa.agent.runtime.core.retry;

@FunctionalInterface
public interface RetryWork<T> {
    T execute(int attempt);
}
