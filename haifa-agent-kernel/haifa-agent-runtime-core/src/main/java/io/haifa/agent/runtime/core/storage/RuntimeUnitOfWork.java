package io.haifa.agent.runtime.core.storage;

import java.util.function.Supplier;

@FunctionalInterface
public interface RuntimeUnitOfWork {
    <T> T execute(Supplier<T> work);

    /**
     * Executes query-only work in a scope that must not reserve the store's writer.
     *
     * <p>A storing adapter that admits only one writer must serve reads without queueing for it, so that listing,
     * paging and polling do not serialize behind a turn's own writes. The scope observes committed state and must
     * fail instead of writing; work that mutates state belongs in {@link #execute(Supplier)}.
     *
     * <p>The default implementation keeps non-transactional stores working: reading and writing are the same scope
     * there.
     */
    default <T> T executeReadOnly(Supplier<T> work) {
        return execute(work);
    }

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
