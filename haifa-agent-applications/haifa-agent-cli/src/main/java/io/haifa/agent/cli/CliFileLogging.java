package io.haifa.agent.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Process-local, bounded file logging for the packaged Coding Agent. */
final class CliFileLogging implements AutoCloseable {
    private static final int LOG_FILE_BYTES = 4 * 1024 * 1024;
    private static final int LOG_FILE_COUNT = 5;
    private static final Logger ROOT = Logger.getLogger("");
    private static final Logger CLI = Logger.getLogger("io.haifa.agent.cli");

    private final FileHandler fileHandler;
    private final Handler[] previousHandlers;
    private final Level previousLevel;
    private final Thread.UncaughtExceptionHandler previousUncaughtHandler;

    private CliFileLogging(
            FileHandler fileHandler,
            Handler[] previousHandlers,
            Level previousLevel,
            Thread.UncaughtExceptionHandler previousUncaughtHandler) {
        this.fileHandler = fileHandler;
        this.previousHandlers = previousHandlers;
        this.previousLevel = previousLevel;
        this.previousUncaughtHandler = previousUncaughtHandler;
    }

    static CliFileLogging open(Map<String, String> environment) throws IOException {
        Path directory = logDirectory(environment);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("Coding Agent log directory is invalid");
        }
        FileHandler handler = new FileHandler(
                directory.resolve("haifa-coding-%g.log").toString(), LOG_FILE_BYTES, LOG_FILE_COUNT, true);
        handler.setEncoding(StandardCharsets.UTF_8.name());
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.INFO);

        Handler[] previousHandlers = ROOT.getHandlers();
        Level previousLevel = ROOT.getLevel();
        for (Handler previous : previousHandlers) ROOT.removeHandler(previous);
        ROOT.addHandler(handler);
        ROOT.setLevel(Level.INFO);
        Thread.UncaughtExceptionHandler previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
        var logging = new CliFileLogging(handler, previousHandlers, previousLevel, previousUncaughtHandler);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> logging.logUncaught(thread, failure));
        CLI.log(Level.INFO, "CLI_START");
        return logging;
    }

    void completed(int exitCode) {
        CLI.log(Level.INFO, "CLI_EXIT code={0}", exitCode);
        fileHandler.flush();
    }

    void logUncaught(Thread thread, Throwable failure) {
        String threadName =
                Objects.requireNonNull(thread, "thread must not be null").getName();
        Throwable checked = Objects.requireNonNull(failure, "failure must not be null");
        String frames = Arrays.stream(checked.getStackTrace())
                .limit(16)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" <- "));
        CLI.log(Level.SEVERE, "CLI_UNCAUGHT thread={0} type={1} frames={2}", new Object[] {
            threadName, checked.getClass().getName(), frames
        });
        fileHandler.flush();
    }

    static Path logDirectory(Map<String, String> environment) {
        String configured = Objects.requireNonNull(environment, "environment must not be null")
                .get("HAIFA_LOG_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) throw new IllegalStateException("user.home is unavailable");
        return Path.of(userHome, ".haifa-agent", "coding", "logs")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public void close() {
        CLI.log(Level.INFO, "CLI_LOGGING_STOP");
        fileHandler.flush();
        ROOT.removeHandler(fileHandler);
        fileHandler.close();
        for (Handler previous : previousHandlers) ROOT.addHandler(previous);
        ROOT.setLevel(previousLevel);
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
    }
}
