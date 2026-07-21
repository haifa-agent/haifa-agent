package io.haifa.agent.application.project.tool;

import io.haifa.agent.runtime.core.tool.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Produces a frozen subset of definitions for the existing Runtime ToolRegistry. */
public final class ProjectToolCatalog {
    private static final Map<String, String> REQUIRED_CAPABILITY = Map.ofEntries(
            Map.entry("file.list", "file.read"), Map.entry("file.stat", "file.read"),
            Map.entry("file.read", "file.read"), Map.entry("file.search", "file.read"),
            Map.entry("file.create", "file.write"), Map.entry("file.write", "file.write"),
            Map.entry("file.delete", "file.write"), Map.entry("file.move", "file.write"),
            Map.entry("file.diff", "file.read"), Map.entry("file.patch", "file.write"),
            Map.entry("git.inspect", "git.read"), Map.entry("git.status", "git.read"),
            Map.entry("git.diff", "git.read"), Map.entry("execution.run", "execution.run"));

    private static final List<ToolDefinition> DEFINITIONS = REQUIRED_CAPABILITY.keySet().stream()
            .sorted()
            .map(name -> new ToolDefinition(
                    name,
                    "1.0",
                    "haifa." + name + ".input.v1",
                    name.startsWith("file.")
                                    && Set.of("file.create", "file.write", "file.delete", "file.move", "file.patch")
                                            .contains(name)
                            || name.equals("execution.run")))
            .toList();

    public FrozenToolDisclosure disclose(
            Set<String> configuredTools, Set<String> effectiveCapabilities, boolean modelSupportsTools) {
        Objects.requireNonNull(configuredTools, "configuredTools must not be null");
        Objects.requireNonNull(effectiveCapabilities, "effectiveCapabilities must not be null");
        if (!modelSupportsTools) return new FrozenToolDisclosure(List.of(), digest(List.of()));
        List<ToolDefinition> disclosed = DEFINITIONS.stream()
                .filter(tool -> configuredTools.contains(tool.name()))
                .filter(tool -> effectiveCapabilities.contains(REQUIRED_CAPABILITY.get(tool.name())))
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
        return new FrozenToolDisclosure(disclosed, digest(disclosed));
    }

    private static String digest(List<ToolDefinition> tools) {
        String canonical = tools.stream()
                .map(tool ->
                        tool.name() + "@" + tool.version() + ":" + tool.inputSchemaId() + ":" + tool.sideEffecting())
                .sorted()
                .reduce("", (left, right) -> left + "\n" + right);
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record FrozenToolDisclosure(List<ToolDefinition> definitions, String digest) {
        public FrozenToolDisclosure {
            definitions = List.copyOf(definitions);
            digest = Objects.requireNonNull(digest, "digest must not be null");
        }
    }
}
