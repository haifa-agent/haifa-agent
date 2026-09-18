package io.haifa.agent.runtime.core.storage;

import java.util.function.Supplier;

@FunctionalInterface
public interface RuntimeUnitOfWork {
    <T> T execute(Supplier<T> work);

    /**
     * Registers observational work that may run only after the outermost durable unit of work commits.
     *
     * <p>Non-transactional implementations execute the callback immediately. Transactional adapters must defer it
     * until commit and discard it on rollback.
     */
    default void afterCommit(Runnable listener) {
        listener.run();
    }
}
