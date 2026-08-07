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

/** Process-local scheduler guarded by an OS file lock; a second Server fails closed. */
public final class MissionDispatcher implements AutoCloseable {
    private final SqliteMissionStore store;
    private final MissionExecutionCoordinator coordinator;
    private final Clock clock;
    private final Path lockPath;
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("pa-mission-dispatcher").daemon(true).factory());
    private FileChannel channel;
    private FileLock lock;

    public MissionDispatcher(
            SqliteMissionStore store, MissionExecutionCoordinator coordinator, Clock clock, Path dataDirectory) {
        this.store = Objects.requireNonNull(store);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.clock = Objects.requireNonNull(clock);
        this.lockPath = Objects.requireNonNull(dataDirectory)
                .toAbsolutePath()
                .normalize()
                .resolve("mission-dispatcher.lock");
    }

    public void start() {
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                throw new MissionException(
                        "MISSION_DISPATCHER_ALREADY_RUNNING", "Another Mission dispatcher owns this data directory");
            }
            Instant now = now();
            store.registerDispatcher(Long.toString(ProcessHandle.current().pid()), instanceId, now);
            executor.scheduleWithFixedDelay(this::cycle, 0, 500, TimeUnit.MILLISECONDS);
        } catch (IOException | java.nio.channels.OverlappingFileLockException exception) {
            close();
            throw new MissionException(
                    "MISSION_DISPATCHER_ALREADY_RUNNING", "Mission dispatcher lock is unavailable", exception);
        }
    }

    private void cycle() {
        try {
            store.heartbeatDispatcher(instanceId, now());
            coordinator.tick();
        } catch (RuntimeException ignored) {
            // The next bounded cycle performs recovery. Details stay in safe operational diagnostics.
        }
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            if (lock != null) lock.release();
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
        }
    }
}
