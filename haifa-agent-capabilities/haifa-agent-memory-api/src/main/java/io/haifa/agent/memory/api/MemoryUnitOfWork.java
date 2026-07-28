package io.haifa.agent.memory.api;

import java.util.Objects;
import java.util.function.Supplier;

public interface MemoryUnitOfWork {
    <T> T execute(Supplier<T> work);

    default void afterCommit(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null").run();
    }

    static MemoryUnitOfWork direct() {
        return new MemoryUnitOfWork() {
            @Override
            public <T> T execute(Supplier<T> work) {
                return Objects.requireNonNull(work, "work must not be null").get();
            }
        };
    }
}
