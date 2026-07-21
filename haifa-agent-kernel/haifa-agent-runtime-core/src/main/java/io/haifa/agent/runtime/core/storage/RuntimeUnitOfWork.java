package io.haifa.agent.runtime.core.storage;

import java.util.function.Supplier;

@FunctionalInterface
public interface RuntimeUnitOfWork {
    <T> T execute(Supplier<T> work);
}
