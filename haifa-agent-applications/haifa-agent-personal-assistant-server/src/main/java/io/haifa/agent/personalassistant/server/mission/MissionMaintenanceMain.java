package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;

/** Offline, fail-closed maintenance entrypoint used through the executable Server JAR. */
public final class MissionMaintenanceMain {
    private MissionMaintenanceMain() {}

    public static void run(String[] args) {
        if (args.length != 5) {
            throw new MissionException(
                    "MISSION_MAINTENANCE_ARGUMENTS_INVALID",
                    "Usage: mission-maintenance <backup|restore|verify> <source> <target-or-dash> <product-digest> <skill-binding>");
        }
        String operation = args[0];
        Path source = Path.of(args[1]).toAbsolutePath().normalize();
        Path target =
                "-".equals(args[2]) ? null : Path.of(args[2]).toAbsolutePath().normalize();
        String productDigest = args[3];
        String skillBinding = args[4];
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.systemUTC();
        switch (operation) {
            case "backup" -> backup(source, requireTarget(target), mapper, clock, productDigest, skillBinding);
            case "restore" ->
                new MissionBackupService(mapper, clock, productDigest, skillBinding)
                        .restore(source, requireTarget(target));
            case "verify" -> {
                if (target != null) {
                    throw new MissionException(
                            "MISSION_MAINTENANCE_ARGUMENTS_INVALID", "Verify target must be '-' (no output directory)");
                }
                new MissionBackupService(mapper, clock, productDigest, skillBinding).verify(source);
            }
            default ->
                throw new MissionException(
                        "MISSION_MAINTENANCE_ARGUMENTS_INVALID",
                        "Maintenance operation must be backup, restore, or verify");
        }
    }

    private static void backup(
            Path dataDirectory,
            Path target,
            ObjectMapper mapper,
            Clock clock,
            String productDigest,
            String skillBinding) {
        Path database = dataDirectory.resolve("personal-assistant.sqlite");
        if (!Files.isRegularFile(database)) {
            throw new MissionException("MISSION_STORE_UNAVAILABLE", "Personal Assistant database is unavailable");
        }
        Path lockPath = dataDirectory.resolve("mission-dispatcher.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = requireOfflineLock(channel)) {
            SqliteMissionStore store = new SqliteMissionStore(database, mapper);
            MissionRuntimeAccess runtime = request -> {
                throw new MissionException("MISSION_MAINTENANCE_ONLY", "Runtime is unavailable in maintenance mode");
            };
            var coordinator = new MissionExecutionCoordinator(store, runtime, clock, "offline-maintenance");
            try (var dispatcher = new MissionDispatcher(store, coordinator, clock, dataDirectory)) {
                new MissionBackupService(store, dispatcher, mapper, clock, productDigest, skillBinding).create(target);
            }
        } catch (IOException | OverlappingFileLockException exception) {
            throw new MissionException(
                    "MISSION_SERVER_MUST_BE_STOPPED", "Stop the Personal Assistant Server before backup", exception);
        }
    }

    private static FileLock requireOfflineLock(FileChannel channel) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock == null) {
            throw new MissionException(
                    "MISSION_SERVER_MUST_BE_STOPPED", "Stop the Personal Assistant Server before backup");
        }
        return lock;
    }

    private static Path requireTarget(Path target) {
        if (target == null) {
            throw new MissionException(
                    "MISSION_MAINTENANCE_ARGUMENTS_INVALID", "Operation requires a target directory");
        }
        return target;
    }
}
