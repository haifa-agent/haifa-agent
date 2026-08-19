package io.haifa.agent.orchestration.core.spi;

import java.util.function.Supplier;

/** Transaction boundary shared with Runtime when the same adapter implements both ports. */
@FunctionalInterface
public interface WorkflowUnitOfWork {
    <T> T execute(Supplier<T> work);
}
