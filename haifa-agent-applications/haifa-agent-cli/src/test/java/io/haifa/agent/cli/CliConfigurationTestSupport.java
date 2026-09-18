package io.haifa.agent.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

final class CliConfigurationTestSupport {

    private CliConfigurationTestSupport() {}

    static CliConfiguration loadConfiguration(String yamlContent) throws IOException {
        return loadConfiguration(yamlContent, (Function<String, String>) null);
    }

    static CliConfiguration loadConfiguration(String yamlContent, String... extraArgs) throws IOException {
        return loadConfiguration(yamlContent, null, extraArgs);
    }

    static CliConfiguration loadConfiguration(
            String yamlContent, Function<String, String> environment, String... extraArgs) throws IOException {
        Path tempFile = Files.createTempFile("haifa-cli-test", ".yaml");
        try {
            Files.writeString(tempFile, yamlContent);
            List<String> argsList = new ArrayList<>();
            argsList.add("--config");
            argsList.add(tempFile.toString());
            if (extraArgs != null && extraArgs.length > 0) {
                argsList.addAll(Arrays.asList(extraArgs));
            }
            CliConfigurationLoader loader =
                    environment != null ? new CliConfigurationLoader(environment) : new CliConfigurationLoader();
            return loader.load(CliArguments.parse(argsList.toArray(String[]::new)), Path.of("."));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    static Path writeTempConfigFile(Path dir, String fileName, String yamlContent) throws IOException {
        Path target = dir.resolve(fileName);
        Files.writeString(target, yamlContent);
        return target;
    }
}
