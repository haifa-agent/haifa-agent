package io.haifa.agent.cli.distribution;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs a verified, extracted release using its own Java runtime. */
public final class InstallCodingAgent {
    private static final String WINDOWS_MARKER = "@rem Haifa Agent release launcher\r\n";

    private InstallCodingAgent() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected installed package and command directory");
        }
        install(Path.of(args[0]).toAbsolutePath().normalize(), Path.of(args[1]));
    }

    private static void install(Path installedPackage, Path bin) throws IOException {
        String releaseId = installedPackage.getFileName().toString();
        if (!releaseId.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?-[a-f0-9]{16}")) {
            throw new IllegalArgumentException("Invalid release ID");
        }
        Path packages = installedPackage.getParent();
        boolean windows = packages.getFileSystem().getSeparator().equals("\\");
        String launcher = windows ? "haifa.bat" : "haifa";
        Path command = bin.resolve(launcher);
        Path installedLauncher = installedPackage.resolve("bin").resolve(launcher);
        if (!Files.isRegularFile(installedLauncher) || !Files.isExecutable(installedLauncher)) {
            throw new IOException("Installed release is missing its executable launcher");
        }
        if (!Files.isRegularFile(installedPackage.resolve("haifa-coding.yaml"))) {
            throw new IOException("Installed release is missing its configuration: " + installedPackage);
        }
        Files.createDirectories(bin);
        if (!isManagedCommand(command, packages, windows)) {
            throw new IOException("An existing command already occupies " + command);
        }
        activate(command, installedLauncher, releaseId, windows);
    }

    private static boolean isManagedCommand(Path command, Path packages, boolean windows) throws IOException {
        if (!Files.exists(command, NOFOLLOW_LINKS)) {
            return true;
        }
        if (windows) {
            return Files.isRegularFile(command, NOFOLLOW_LINKS)
                    && Files.size(command) < 512
                    && Files.readString(command).startsWith(WINDOWS_MARKER);
        }
        return Files.isSymbolicLink(command)
                && Files.readSymbolicLink(command).normalize().startsWith(packages);
    }

    private static void activate(Path command, Path launcher, String releaseId, boolean windows) throws IOException {
        Path staging = Files.createTempDirectory(command.getParent(), ".haifa-command-");
        Path next = staging.resolve(command.getFileName());
        try {
            if (windows) {
                Files.writeString(
                        next, WINDOWS_MARKER + "@\"%~dp0..\\packages\\" + releaseId + "\\bin\\haifa.bat\" %*\r\n");
            } else {
                Files.createSymbolicLink(next, launcher);
            }
            Files.move(next, command, ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(next);
            Files.delete(staging);
        }
    }
}
