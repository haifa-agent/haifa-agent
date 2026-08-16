package io.haifa.agent.testing.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The single strict loader for platform matrices in the independent configuration repository. */
public final class PlatformManifestLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public PlatformManifest load(Path configRoot, String matrixId) {
        Path root = Objects.requireNonNull(configRoot, "configRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("test config root must be a directory");
        PlatformManifest.identifier(matrixId, "matrix id");
        Path file = root.resolve("matrices").resolve(matrixId + ".yaml").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("platform manifest is unavailable: " + matrixId);
        }
        try {
            JsonNode rootNode = yaml.readTree(file.toFile());
            int schemaVersion = required(rootNode, "schemaVersion").asInt();
            String loadedId = required(rootNode, "matrixId").asText();
            if (!matrixId.equals(loadedId)) {
                throw new IllegalArgumentException("platform manifest id does not match requested matrix");
            }
            String strategy = required(rootNode, "strategy").asText();
            JsonNode combinations = required(rootNode, "combinations");
            if (!combinations.isArray()) throw new IllegalArgumentException("platform combinations must be an array");
            List<PlatformManifest.PlatformProfile> profiles = new ArrayList<>();
            for (JsonNode combination : combinations) profiles.add(profile(combination));
            return new PlatformManifest(schemaVersion, loadedId, strategy, profiles);
        } catch (IOException exception) {
            throw new IllegalArgumentException("platform manifest cannot be parsed: " + matrixId, exception);
        }
    }

    private PlatformManifest.PlatformProfile profile(JsonNode node) {
        String id = required(node, "id").asText();
        String platform = required(node, "platform").asText();
        LinkedHashMap<String, Object> extensions = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equals("id") && !field.getKey().equals("platform")) {
                extensions.put(field.getKey(), yaml.convertValue(field.getValue(), Object.class));
            }
        }
        return new PlatformManifest.PlatformProfile(id, platform, extensions);
    }

    private static JsonNode required(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.hasNonNull(field)) {
            throw new IllegalArgumentException("platform manifest field is required: " + field);
        }
        return node.get(field);
    }
}
