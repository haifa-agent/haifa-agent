package io.haifa.agent.mcp.tool;

import io.haifa.agent.mcp.config.McpServerId;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Debounces tools/list_changed and creates reviewable candidates without mutating frozen bindings. */
public final class McpToolRefreshCoordinator implements AutoCloseable {
    private final CandidateDiscovery discovery;
    private final Map<McpServerId, McpDiscoveryContext> contexts;
    private final Clock clock;
    private final Duration debounce;
    private final BiConsumer<McpToolCatalogCandidateSnapshot, Throwable> resultConsumer;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<McpServerId, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<McpServerId, McpToolCatalogCandidateSnapshot> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<McpServerId, AtomicLong> generations = new ConcurrentHashMap<>();

    public McpToolRefreshCoordinator(
            McpToolDiscoveryService discovery,
            Map<McpServerId, McpDiscoveryContext> contexts,
            Clock clock,
            Duration debounce,
            BiConsumer<McpToolCatalogCandidateSnapshot, Throwable> resultConsumer) {
        this(discovery::discover, contexts, clock, debounce, resultConsumer);
    }

    public McpToolRefreshCoordinator(
            CandidateDiscovery discovery,
            Map<McpServerId, McpDiscoveryContext> contexts,
            Clock clock,
            Duration debounce,
            BiConsumer<McpToolCatalogCandidateSnapshot, Throwable> resultConsumer) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.contexts = Map.copyOf(Objects.requireNonNull(contexts, "contexts"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.debounce = Objects.requireNonNull(debounce, "debounce");
        if (debounce.isNegative() || debounce.isZero()) throw new IllegalArgumentException("debounce must be positive");
        this.resultConsumer = Objects.requireNonNull(resultConsumer, "resultConsumer");
        this.executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("haifa-mcp-tool-refresh", 0)
                .daemon(true)
                .factory());
    }

    public void signal(String serverId) {
        signal(new McpServerId(serverId));
    }

    public void signal(McpServerId serverId) {
        Objects.requireNonNull(serverId, "serverId");
        if (!contexts.containsKey(serverId)) return;
        pending.compute(serverId, (key, previous) -> {
            if (previous != null) previous.cancel(false);
            return executor.schedule(() -> refresh(key), debounce.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    public Optional<McpToolCatalogCandidateSnapshot> latest(McpServerId serverId) {
        return Optional.ofNullable(latest.get(serverId));
    }

    private void refresh(McpServerId serverId) {
        pending.remove(serverId);
        try {
            var candidates = discovery.discover(serverId, contexts.get(serverId));
            long generation = generations
                    .computeIfAbsent(serverId, ignored -> new AtomicLong())
                    .incrementAndGet();
            var snapshot = new McpToolCatalogCandidateSnapshot(serverId, generation, clock.instant(), candidates);
            latest.put(serverId, snapshot);
            resultConsumer.accept(snapshot, null);
        } catch (RuntimeException exception) {
            resultConsumer.accept(null, exception);
        }
    }

    @Override
    public void close() {
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface CandidateDiscovery {
        java.util.List<McpToolImportCandidate> discover(McpServerId serverId, McpDiscoveryContext context);
    }
}
