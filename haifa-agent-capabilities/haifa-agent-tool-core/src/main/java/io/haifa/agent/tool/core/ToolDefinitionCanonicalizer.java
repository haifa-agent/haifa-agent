package io.haifa.agent.tool.core;

import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolDefinitionHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolDefinitionCanonicalizer implements ToolDefinitionHasher {
    @Override
    public ToolDefinitionHash hash(ToolDefinition definition) {
        return new ToolDefinitionHash(sha256(canonicalize(asDocument(definition))));
    }

    public String canonicalize(Object value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.toString();
    }

    private static Map<String, Object> asDocument(ToolDefinition definition) {
        var document = new LinkedHashMap<String, Object>();
        document.put("name", definition.name().value());
        document.put("version", definition.version().value());
        document.put("providerId", definition.providerId().value());
        document.put("title", definition.title());
        document.put("description", definition.description());
        document.put("inputSchemaId", definition.inputSchema().id());
        document.put("inputSchemaVersion", definition.inputSchema().version());
        document.put("inputSchema", definition.inputSchema().document());
        document.put("outputSchemaId", definition.outputSchema().id());
        document.put("outputSchemaVersion", definition.outputSchema().version());
        document.put("outputSchema", definition.outputSchema().document());
        document.put("executionMode", definition.executionMode().name());
        document.put("cancellationSupported", definition.cancellationSupported());
        document.put("timeoutMillis", definition.timeout().toMillis());
        document.put("concurrencyPolicy", definition.concurrencyPolicy());
        document.put("idempotency", definition.idempotency().name());
        document.put("risk", definition.risk().name());
        document.put("sideEffects", sortedNames(definition.sideEffects()));
        document.put("filesystemCapabilities", sorted(definition.resources().filesystemCapabilities()));
        document.put("networkHosts", sorted(definition.resources().networkHosts()));
        document.put("executionProfiles", sorted(definition.resources().executionProfiles()));
        document.put(
                "credentials",
                definition.credentialRequirements().stream()
                        .map(ToolDefinitionCanonicalizer::credentialDocument)
                        .toList());
        document.put("approvalRequirement", definition.approvalRequirement().name());
        document.put("provenance", definition.provenance());
        document.put("deprecated", definition.deprecated());
        document.put("tags", sorted(definition.tags()));
        return document;
    }

    private static Map<String, Object> credentialDocument(CredentialRequirement requirement) {
        return Map.of(
                "definitionId", requirement.definitionId().value(),
                "purpose", requirement.purpose(),
                "scopes", sorted(requirement.scopes()),
                "exposureMode", requirement.exposureMode().name());
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static List<String> sortedNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static void append(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            output.append('"');
            text.codePoints().forEach(codePoint -> appendEscaped(codePoint, output));
            output.append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) output.append(',');
                append(String.valueOf(entries.get(index).getKey()), output);
                output.append(':');
                append(entries.get(index).getValue(), output);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> values) {
            output.append('[');
            int index = 0;
            for (Object element : values) {
                if (index++ > 0) output.append(',');
                append(element, output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException(
                    "unsupported canonical value " + value.getClass().getName());
        }
    }

    private static void appendEscaped(int codePoint, StringBuilder output) {
        switch (codePoint) {
            case '"' -> output.append("\\\"");
            case '\\' -> output.append("\\\\");
            case '\b' -> output.append("\\b");
            case '\f' -> output.append("\\f");
            case '\n' -> output.append("\\n");
            case '\r' -> output.append("\\r");
            case '\t' -> output.append("\\t");
            default -> {
                if (codePoint < 0x20) output.append(String.format("\\u%04x", codePoint));
                else output.appendCodePoint(codePoint);
            }
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
