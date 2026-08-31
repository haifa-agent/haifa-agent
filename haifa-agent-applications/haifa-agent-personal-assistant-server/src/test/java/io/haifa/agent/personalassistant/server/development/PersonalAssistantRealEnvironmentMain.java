package io.haifa.agent.personalassistant.server.development;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** IDE-only launcher for the complete Personal Assistant real environment using compiled classes. */
public final class PersonalAssistantRealEnvironmentMain {
    static final String DEVELOPMENT_CLASSPATH_ENVIRONMENT = "HAIFA_PERSONAL_DEV_CLASSPATH";
    private static final String PYTHON_COMMAND_ENVIRONMENT = "HAIFA_PYTHON_COMMAND";

    private PersonalAssistantRealEnvironmentMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        Path repository = findRepository(Path.of(System.getProperty("user.dir")));
        String python = System.getenv().getOrDefault(PYTHON_COMMAND_ENVIRONMENT, defaultPythonCommand());
        ProcessBuilder process = new ProcessBuilder(command(repository, python, args));
        process.directory(repository.toFile());
        String classpath = absoluteClasspath(
                System.getProperty("java.class.path"), Path.of(System.getProperty("user.dir")));
        process.environment().put(DEVELOPMENT_CLASSPATH_ENVIRONMENT, classpath);
        process.inheritIO();
        int exitCode = process.start().waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Personal Assistant real environment exited with code " + exitCode);
        }
    }

    static Path findRepository(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("scripts/real_environment.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root containing scripts/real_environment.py");
    }

    static List<String> command(Path repository, String python, String[] args) {
        List<String> command = new ArrayList<>();
        command.add(python);
        command.add(repository.resolve("scripts/real_environment.py").toString());
        command.addAll(Arrays.asList(args));
        command.add("--backend-launch-mode");
        command.add("classpath");
        return List.copyOf(command);
    }

    static String absoluteClasspath(String classpath, Path baseDirectory) {
        return Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : baseDirectory.resolve(path))
                .map(path -> path.toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static String defaultPythonCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "python" : "python3";
    }
}
