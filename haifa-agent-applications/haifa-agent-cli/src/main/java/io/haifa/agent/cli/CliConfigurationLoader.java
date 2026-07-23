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
        String providerId = environment("HAIFA_MODEL_PROVIDER_ID")
                .orElseGet(() -> text(model, "providerId", defaults.model().providerId()));
        boolean bailian = providerId.equals("aliyun-bailian");
        String modelId = arguments.model().orElseGet(() -> environment("HAIFA_MODEL_ID")
                .orElseGet(() -> text(
                        model,
                        "modelId",
                        bailian ? "qwen-plus" : defaults.model().modelId())));
        String endpoint = environment("HAIFA_MODEL_ENDPOINT").orElseGet(() -> nullableText(model, "endpoint"));
        String credential = environment("HAIFA_CREDENTIAL_REF")
                .orElseGet(() -> text(
                        model,
                        "credentialRef",
                        bailian ? "env://DASHSCOPE_API_KEY" : defaults.model().credentialRef()));
        String workspaceId =
                environment("HAIFA_BAILIAN_WORKSPACE_ID").orElseGet(() -> nullableText(model, "workspaceId"));
        String region = environment("HAIFA_BAILIAN_REGION").orElseGet(() -> nullableText(model, "region"));
        if (!bailian && endpoint == null) endpoint = defaults.model().endpoint().toString();
        Set<String> tools = stringSet(object(source, "tools").get("enabled"), defaults.enabledTools());
        List<CliConfiguration.McpServer> mcpServers = mcpServers(object(source, "mcp"));
        CliConfiguration.Web web = web(object(source, "web"), defaults.web());
        CliConfiguration.Execution execution = execution(object(source, "execution"), defaults.execution());
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
                new CliConfiguration.Model(
                        providerId,
                        modelId,
                        endpoint == null ? null : java.net.URI.create(endpoint),
                        credential,
                        workspaceId,
                        region),
                tools,
                mcpServers,
                web,
                execution,
                approval,
                timeout,
                Math.toIntExact(number(runtime, "maxIterations", defaults.maxIterations())),
                number(runtime, "maxToolCalls", defaults.maxToolCalls()));
    }

    private static CliConfiguration.Web web(Map<String, Object> source, CliConfiguration.Web defaults) {
        return new CliConfiguration.Web(
                webProvider(object(source, "search"), "search", defaults.search()),
                webProvider(object(source, "fetch"), "fetch", defaults.fetch()));
    }

    private static CliConfiguration.WebProvider webProvider(
            Map<String, Object> source, String operation, CliConfiguration.WebProvider defaults) {
        String providerId = text(source, "provider", defaults.providerId()).toLowerCase(java.util.Locale.ROOT);
        String endpoint = nullableText(source, "endpoint");
        if (endpoint == null)
            endpoint = defaultWebEndpoint(operation, providerId).toString();
        String credentialRef = nullableText(source, "credentialRef");
        if (credentialRef == null) credentialRef = defaultWebCredential(providerId);
        return new CliConfiguration.WebProvider(
                bool(source, "enabled", defaults.enabled()),
                providerId,
                java.net.URI.create(endpoint),
                credentialRef,
                Duration.ofMillis(
                        number(source, "timeoutMillis", defaults.timeout().toMillis())),
                Math.toIntExact(number(source, "maxResponseBytes", defaults.maxResponseBytes())));
    }

    private static java.net.URI defaultWebEndpoint(String operation, String providerId) {
        if (operation.equals("fetch")) {
            if (!providerId.equals("aliyun")) {
                throw new IllegalArgumentException("web.fetch.provider must be aliyun");
            }
            return io.haifa.agent.application.project.tool.web.provider.AliyunFetchProvider.DEFAULT_ENDPOINT;
        }
        return switch (providerId) {
            case "aliyun" -> io.haifa.agent.application.project.tool.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT;
            case "brave" ->
                io.haifa.agent.application.project.tool.web.provider.BraveWebSearchProvider.DEFAULT_ENDPOINT;
            case "tavily" ->
                io.haifa.agent.application.project.tool.web.provider.TavilyWebSearchProvider.DEFAULT_ENDPOINT;
            default -> throw new IllegalArgumentException("web.search.provider is unsupported");
        };
    }

    private static String defaultWebCredential(String providerId) {
        return switch (providerId) {
            case "aliyun" -> "env://ALIYUN_IQS_API_KEY";
            case "brave" -> "env://BRAVE_SEARCH_API_KEY";
            case "tavily" -> "env://TAVILY_API_KEY";
            default -> throw new IllegalArgumentException("web providerId is unsupported");
        };
    }

    private static CliConfiguration.Execution execution(
            Map<String, Object> source, CliConfiguration.Execution defaults) {
        String shell = text(source, "shell", defaults.shell());
        String shellPathValue = nullableText(source, "shellPath");
        java.nio.file.Path shellPath = shellPathValue == null ? null : java.nio.file.Path.of(shellPathValue);
        return new CliConfiguration.Execution(
                shell,
                shellPath,
                Duration.ofMillis(number(
                        source,
                        "defaultTimeoutMillis",
                        defaults.defaultTimeout().toMillis())),
                Duration.ofMillis(number(
                        source, "maxTimeoutMillis", defaults.maximumTimeout().toMillis())),
                Math.toIntExact(number(source, "maxOutputBytes", defaults.maxOutputBytes())),
                Math.toIntExact(number(source, "maxOutputLines", defaults.maxOutputLines())),
                Math.toIntExact(number(source, "maxProcesses", defaults.maxProcesses())),
                stringSet(source.get("inheritEnvironment"), defaults.inheritEnvironment()));
    }

    private static List<CliConfiguration.McpServer> mcpServers(Map<String, Object> mcp) {
        Object configured = mcp.get("servers");
        if (configured == null) return List.of();
        if (!(configured instanceof List<?> servers)) {
            throw new IllegalArgumentException("configuration mcp.servers must be a list");
        }
        List<CliConfiguration.McpServer> result = new ArrayList<>();
        for (Object item : servers) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("configuration mcp.servers must contain objects");
            }
            Map<String, Object> server = new LinkedHashMap<>();
            raw.forEach((key, value) -> server.put(String.valueOf(key), value));
            String id = requiredText(server, "id", "configuration mcp server id");
            String displayName = text(server, "displayName", id);
            String endpoint = requiredText(server, "endpoint", "configuration mcp server endpoint");
            result.add(new CliConfiguration.McpServer(
                    id,
                    displayName,
                    java.net.URI.create(endpoint),
                    bool(server, "allowLoopbackHttp", false),
                    stringSet(server.get("allowedTools"), Set.of()),
                    text(server, "aliasNamespace", id.replace('-', '_')),
                    text(server, "policyProfile", "conservative"),
                    Duration.ofMillis(number(server, "connectTimeoutMillis", 5000)),
                    Duration.ofMillis(number(server, "requestTimeoutMillis", 15000)),
                    Duration.ofMillis(number(server, "idleTimeoutMillis", 30000)),
                    Math.toIntExact(number(server, "maxBodyBytes", 4 * 1024 * 1024)),
                    Math.toIntExact(number(server, "maxHeaderBytes", 32 * 1024)),
                    Math.toIntExact(nonNegativeNumber(server, "maxReconnectAttempts", 1))));
        }
        return List.copyOf(result);
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

    private static String requiredText(Map<String, Object> source, String key, String field) {
        Object value = source.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be text");
        return CliConfiguration.text(text, field);
    }

    private static String nullableText(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("configuration " + key + " must be text");
        }
        return CliConfiguration.text(text, "configuration " + key);
    }

    private static boolean bool(Map<String, Object> source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("configuration " + key + " must be boolean");
        }
        return flag;
    }

    private static long number(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException("configuration " + key + " must be a positive number");
        }
        return number.longValue();
    }

    private static long nonNegativeNumber(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException("configuration " + key + " must be a non-negative number");
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
