package io.haifa.agent.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class CliConfigurationLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    CliConfiguration load(CliArguments arguments, Path workspace) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Map<String, Object> values = new LinkedHashMap<>();
        if (arguments.config().isPresent()) {
            values.putAll(read(arguments.config().orElseThrow()));
        } else {
            userConfiguration().filter(Files::isRegularFile).map(this::read).ifPresent(values::putAll);
            Path local = workspace.resolve(".haifa-agent").resolve("config.yaml");
            if (Files.isRegularFile(local)) values.putAll(read(local));
        }
        return resolve(values, arguments);
    }

    private CliConfiguration resolve(Map<String, Object> source, CliArguments arguments) {
        CliConfiguration defaults = CliConfiguration.defaults();
        Map<String, Object> model = object(source, "model");
        String providerId = text(model, "providerId", defaults.model().providerId());
        String modelId = arguments.model().orElseGet(() -> environment("HAIFA_MODEL_ID")
                .orElseGet(() -> text(model, "modelId", defaults.model().modelId())));
        String endpoint = environment("HAIFA_MODEL_ENDPOINT")
                .orElseGet(() ->
                        text(model, "endpoint", defaults.model().endpoint().toString()));
        String credential = environment("HAIFA_CREDENTIAL_REF")
                .orElseGet(() -> text(model, "credentialRef", defaults.model().credentialRef()));
        Set<String> tools = stringSet(object(source, "tools").get("enabled"), defaults.enabledTools());
        ApprovalMode approval = arguments
                .approval()
                .orElseGet(() -> ApprovalMode.parse(text(
                        object(source, "approval"), "mode", defaults.approval().name())));
        Map<String, Object> runtime = object(source, "runtime");
        Duration timeout = arguments
                .timeout()
                .orElseGet(() -> Duration.ofMillis(
                        number(runtime, "maxWallTimeMillis", defaults.timeout().toMillis())));
        return new CliConfiguration(
                new CliConfiguration.Model(providerId, modelId, java.net.URI.create(endpoint), credential),
                tools,
                approval,
                timeout,
                Math.toIntExact(number(runtime, "maxIterations", defaults.maxIterations())),
                number(runtime, "maxToolCalls", defaults.maxToolCalls()));
    }

    private Map<String, Object> read(Path path) {
        try {
            Map<String, Object> values = yaml.readValue(Files.readAllBytes(path), new TypeReference<>() {});
            return values == null ? Map.of() : Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read configuration: " + path.getFileName());
        }
    }

    private static Optional<Path> userConfiguration() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(home, ".haifa-agent", "config.yaml"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw))
            throw new IllegalArgumentException("configuration " + key + " must be an object");
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((entryKey, entryValue) -> values.put(String.valueOf(entryKey), entryValue));
        return values;
    }

    private static String text(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text))
            throw new IllegalArgumentException("configuration " + key + " must be text");
        return CliConfiguration.text(text, "configuration " + key);
    }

    private static long number(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException("configuration " + key + " must be a positive number");
        }
        return number.longValue();
    }

    private static Set<String> stringSet(Object value, Set<String> fallback) {
        if (value == null) return fallback;
        if (!(value instanceof List<?> values))
            throw new IllegalArgumentException("configuration tools.enabled must be a list");
        List<String> names = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("configuration tools.enabled must contain names");
            }
            names.add(name.trim());
        }
        return Set.copyOf(names);
    }

    private static Optional<String> environment(String key) {
        return Optional.ofNullable(System.getenv(key)).map(String::trim).filter(value -> !value.isEmpty());
    }
}
