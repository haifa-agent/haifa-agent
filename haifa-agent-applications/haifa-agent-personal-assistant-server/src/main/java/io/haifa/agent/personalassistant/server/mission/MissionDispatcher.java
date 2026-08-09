package io.haifa.agent.personalassistant.server.mission;

import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Process-local scheduler guarded by an OS file lock; a second Server fails closed. */
public final class MissionDispatcher implements AutoCloseable {
    private final SqliteMissionStore store;
    private final MissionExecutionCoordinator coordinator;
    private final Clock clock;
    private final Path lockPath;
    private final Supplier<MissionCapacityMonitor.CapacitySnapshot> capacity;
    private final long pollMillis;
    private final long shutdownTimeoutMillis;
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("pa-mission-dispatcher").daemon(true).factory());
    private FileChannel channel;
    private FileLock lock;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean maintenancePaused = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicReference<String> status = new AtomicReference<>("NOT_READY");
    private final AtomicLong recoveryCount = new AtomicLong();
    private final AtomicLong lastReconcileLatencyMillis = new AtomicLong();
    private final AtomicLong lastReconcileAtMillis = new AtomicLong();

    public MissionDispatcher(
            SqliteMissionStore store, MissionExecutionCoordinator coordinator, Clock clock, Path dataDirectory) {
        this(
                store,
                coordinator,
                clock,
                dataDirectory,
                () -> new MissionCapacityMonitor.CapacitySnapshot(0, 0, 0, false, false, true, "NONE"),
                500,
                20_000);
    }

    public MissionDispatcher(
            SqliteMissionStore store,
            MissionExecutionCoordinator coordinator,
            Clock clock,
            Path dataDirectory,
            MissionCapacityMonitor capacity,
            long pollMillis,
            long shutdownTimeoutMillis) {
        this(store, coordinator, clock, dataDirectory, capacity::snapshot, pollMillis, shutdownTimeoutMillis);
    }

    private MissionDispatcher(
            SqliteMissionStore store,
            MissionExecutionCoordinator coordinator,
            Clock clock,
            Path dataDirectory,
            Supplier<MissionCapacityMonitor.CapacitySnapshot> capacity,
            long pollMillis,
            long shutdownTimeoutMillis) {
        this.store = Objects.requireNonNull(store);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.clock = Objects.requireNonNull(clock);
        this.capacity = Objects.requireNonNull(capacity);
        if (pollMillis < 100 || pollMillis > 500 || shutdownTimeoutMillis < 1_000 || shutdownTimeoutMillis > 20_000) {
            throw new IllegalArgumentException("Mission dispatcher timing limits are invalid");
        }
        this.pollMillis = pollMillis;
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
        this.lockPath = Objects.requireNonNull(dataDirectory)
                .toAbsolutePath()
                .normalize()
                .resolve("mission-dispatcher.lock");
    }

    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Mission dispatcher already started");
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                throw new MissionException(
                        "MISSION_DISPATCHER_ALREADY_RUNNING", "Another Mission dispatcher owns this data directory");
            }
            Instant now = now();
            store.registerDispatcher(Long.toString(ProcessHandle.current().pid()), instanceId, now);
            cycle();
            executor.scheduleWithFixedDelay(this::cycle, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        } catch (MissionException exception) {
            close();
            throw exception;
        } catch (IOException | java.nio.channels.OverlappingFileLockException exception) {
            close();
            throw new MissionException(
                    "MISSION_DISPATCHER_ALREADY_RUNNING", "Mission dispatcher lock is unavailable", exception);
        }
    }

    private void cycle() {
        if (!started.get()) return;
        long began = System.nanoTime();
        try {
            store.heartbeatDispatcher(instanceId, now());
            MissionCapacityMonitor.CapacitySnapshot currentCapacity = capacity.get();
            boolean allowNewDispatch = !maintenancePaused.get() && currentCapacity.acceptingNewWork();
            coordinator.tick(allowNewDispatch);
            lastReconcileAtMillis.set(now().toEpochMilli());
            if (currentCapacity.acceptingNewWork() && !maintenancePaused.get()) {
                ready.set(true);
                status.set("READY");
            } else {
                ready.set(false);
                status.set(maintenancePaused.get() ? "MAINTENANCE" : "DEGRADED");
            }
        } catch (RuntimeException ignored) {
            // The next bounded cycle performs recovery. Details stay in safe operational diagnostics.
            recoveryCount.incrementAndGet();
            ready.set(false);
            status.set("DEGRADED");
        } finally {
            lastReconcileLatencyMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
        }
    }

    public boolean ready() {
        return ready.get();
    }

    public DispatcherSnapshot snapshot() {
        return new DispatcherSnapshot(
                status.get(),
                ready.get(),
                maintenancePaused.get(),
                recoveryCount.get(),
                lastReconcileLatencyMillis.get(),
                lastReconcileAtMillis.get());
    }

    public <T> T withClaimsPaused(Supplier<T> work) {
        Objects.requireNonNull(work);
        if (!maintenancePaused.compareAndSet(false, true)) {
            throw new MissionException("MISSION_MAINTENANCE_BUSY", "Mission maintenance is already active");
        }
        ready.set(false);
        status.set("MAINTENANCE");
        try {
            return work.get();
        } finally {
            maintenancePaused.set(false);
        }
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    @Override
    public void close() {
        ready.set(false);
        status.set("STOPPING");
        started.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            if (lock != null) lock.release();
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
        status.set("STOPPED");
    }

    public record DispatcherSnapshot(
            String status,
            boolean ready,
            boolean maintenancePaused,
            long recoveryCount,
            long lastReconcileLatencyMillis,
            long lastReconcileAtMillis) {}
}
