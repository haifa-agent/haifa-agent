package io.haifa.agent.sdk.api;

import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.sdk.conversation.ConversationService;
import io.haifa.agent.sdk.product.ProductAssembly;
import io.haifa.agent.sdk.product.ProductAssemblyDiagnostic;
import io.haifa.agent.sdk.product.ProductContribution;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fully assembled product runtime with stable product-facing services. */
public final class HaifaAgent implements AutoCloseable {
    private final ProductAssembly assembly;
    private final AgentRuns runs;
    private final ConversationService conversations;
    private final LocalExecutionScheduler scheduler;
    private final List<ProductContribution> lifecycle;
    private final AtomicBoolean closed = new AtomicBoolean();

    HaifaAgent(
            ProductAssembly assembly,
            AgentRuns runs,
            ConversationService conversations,
            LocalExecutionScheduler scheduler,
            List<ProductContribution> lifecycle) {
        this.assembly = Objects.requireNonNull(assembly, "assembly must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.lifecycle = List.copyOf(Objects.requireNonNull(lifecycle, "lifecycle must not be null"));
    }

    public ProductAssembly assembly() {
        return assembly;
    }

    public List<ProductAssemblyDiagnostic> diagnostics() {
        return assembly.diagnostics();
    }

    public AgentRuns runs() {
        requireOpen();
        return runs;
    }

    public ConversationService conversations() {
        requireOpen();
        return conversations;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        try {
            scheduler.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        for (int index = lifecycle.size() - 1; index >= 0; index--) {
            try {
                lifecycle.get(index).close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("HaifaAgent is closed");
    }
}
