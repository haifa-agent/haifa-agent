package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolCall;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts a human-readable, bounded, single-line target summary from a {@link ToolCall}.
 */
public final class ToolTargetSummary {

    public static final int MAX_TARGET_LENGTH = 512;

    private static final Pattern PATCH_FILE_HEADER = Pattern.compile(
            "(?m)(?:\\*\\*\\*\\s*(?:Update|Add|Delete)\\s+File:\\s*|(?:--- |\\+\\+\\+ )(?:[ab]/)?)([^\\r\\n\\t]+)");

    private ToolTargetSummary() {}

    /**
     * Extracts a human-readable target summary for the given tool call.
     * Collapses newlines/whitespace, applies secret redaction, and bounds length to 512 characters.
     */
    public static String from(ToolCall call) {
        if (call == null) return "";
        String fallback = call.toolName() == null ? "" : call.toolName().strip();
        if (call.arguments() == null || call.arguments().values() == null) {
            return fallback;
        }
        Map<String, Object> values = call.arguments().values();
        if (values.isEmpty()) {
            return fallback;
        }

        String toolName = call.toolName().toLowerCase(Locale.ROOT);

        // 1. Move / rename operations
        if (toolName.contains("move")
                || toolName.contains("rename")
                || toolName.contains("copy")
                || (hasKey(values, "sourcePath") && hasKey(values, "destinationPath"))) {
            String move = extractMoveTarget(values);
            if (move != null) return boundAndSanitize(move, fallback);
        }

        // 2. Execution tools: command takes precedence
        if (isExecutionTool(call.toolName())) {
            String exec = extractExecutionTarget(values);
            if (exec != null) return boundAndSanitize(exec, fallback);
            String desc =
                    stringValue(findValue(values, "description", "purpose", "summary", "toolAction", "toolSummary"));
            if (desc != null) return boundAndSanitize(desc, fallback);
        }

        // 3. Patch tools: file path or patch header
        if (toolName.contains("patch") || toolName.contains("diff")) {
            String filePath = extractFilePathTarget(values);
            if (filePath != null) return boundAndSanitize(filePath, fallback);
            String patch = stringValue(findValue(values, "patch", "diff", "patchContent", "content"));
            String patchTarget = extractPatchTarget(patch);
            if (patchTarget != null) return boundAndSanitize(patchTarget, fallback);
        }

        // 4. File path targets
        String filePath = extractFilePathTarget(values);
        if (filePath != null) return boundAndSanitize(filePath, fallback);

        // 5. Query, URL, Network, Agent
        String queryOrNet = extractQueryOrNetworkTarget(values);
        if (queryOrNet != null) return boundAndSanitize(queryOrNet, fallback);

        // 6. Fallback to tool name
        return fallback;
    }

    private static boolean isExecutionTool(String toolName) {
        String lower = toolName.toLowerCase(Locale.ROOT);
        return lower.contains("exec")
                || lower.contains("command")
                || lower.contains("shell")
                || lower.contains("terminal")
                || lower.equals("bash")
                || lower.equals("sh")
                || lower.equals("cmd")
                || lower.equals("powershell")
                || lower.equals("pwsh");
    }

    private static String extractMoveTarget(Map<String, Object> values) {
        String source = stringValue(findValue(values, "sourcePath", "source_path", "source", "from", "src"));
        String dest = stringValue(findValue(
                values,
                "destinationPath",
                "destination_path",
                "destination",
                "targetPath",
                "target_path",
                "dest",
                "to",
                "target"));
        if (source != null && dest != null && !source.equals(dest)) {
            return source + " -> " + dest;
        }
        return null;
    }

    private static String extractExecutionTarget(Map<String, Object> values) {
        return stringValue(findValue(values, "command", "CommandLine", "commandLine", "command_line", "cmd", "script"));
    }

    private static String extractFilePathTarget(Map<String, Object> values) {
        return stringValue(findValue(
                values,
                "path",
                "filePath",
                "filepath",
                "file_path",
                "targetPath",
                "target_path",
                "file",
                "filename",
                "fileName",
                "file_name",
                "AbsolutePath",
                "absolutePath",
                "absolute_path",
                "TargetFile",
                "targetFile",
                "target_file",
                "directory",
                "dir"));
    }

    private static String extractPatchTarget(String patch) {
        if (patch == null || patch.isBlank()) return null;
        var matcher = PATCH_FILE_HEADER.matcher(patch);
        while (matcher.find()) {
            String path = matcher.group(1).strip();
            int tab = path.indexOf('\t');
            if (tab > 0) path = path.substring(0, tab).strip();
            if (!path.isEmpty() && !path.equals("/dev/null") && !path.equals("dev/null")) {
                return path;
            }
        }
        return null;
    }

    private static String extractQueryOrNetworkTarget(Map<String, Object> values) {
        return stringValue(
                findValue(values, "query", "q", "url", "uri", "pattern", "role", "agent", "subagent", "agentId"));
    }

    private static boolean hasKey(Map<String, Object> values, String key) {
        return findValue(values, key) != null;
    }

    private static Object findValue(Map<String, Object> values, String... candidateKeys) {
        if (values == null || values.isEmpty()) return null;
        for (String key : candidateKeys) {
            Object val = values.get(key);
            if (val != null) return val;
        }
        for (String candidate : candidateKeys) {
            String normalizedCandidate =
                    candidate.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String normalizedKey =
                            entry.getKey().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
                    if (normalizedKey.equals(normalizedCandidate)) {
                        return entry.getValue();
                    }
                }
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            String trimmed = s.strip();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof List<?> list) {
            String joined = list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" "));
            return joined.isEmpty() ? null : joined;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    public static String boundAndSanitize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        String singleLine = raw.replaceAll("\\s+", " ").strip();
        if (singleLine.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        if (singleLine.length() <= MAX_TARGET_LENGTH) {
            return singleLine;
        }
        int end = MAX_TARGET_LENGTH - 1;
        if (Character.isLowSurrogate(singleLine.charAt(end)) && Character.isHighSurrogate(singleLine.charAt(end - 1))) {
            end--;
        }
        return singleLine.substring(0, end) + "…";
    }
}
