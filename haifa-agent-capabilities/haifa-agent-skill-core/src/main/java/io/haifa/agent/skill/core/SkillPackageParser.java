package io.haifa.agent.skill.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillDeclaredVersion;
import io.haifa.agent.skill.api.SkillDiagnostic;
import io.haifa.agent.skill.api.SkillDiagnosticSeverity;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.tool.api.ToolAlias;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Parses only data-oriented YAML front matter and never executes package content. */
public final class SkillPackageParser {
    private static final int MAX_FRONT_MATTER_BYTES = 32 * 1024;
    private static final Set<String> FRONT_MATTER_FIELDS =
            Set.of("name", "description", "license", "compatibility", "metadata", "allowed-tools");
    private static final Set<String> READABLE_EXTENSIONS =
            Set.of("md", "txt", "json", "yaml", "yml", "csv", "xml", "properties", "sh", "ps1", "py", "js", "ts");

    private final SkillPackageLimits limits;
    private final ObjectMapper yaml;

    public SkillPackageParser(SkillPackageLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits must not be null");
        YAMLFactory factory = YAMLFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(16)
                        .maxStringLength(8_192)
                        .maxNumberLength(128)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.yaml = new ObjectMapper(factory);
    }

    public SkillPackageParseResult parseDirectory(Path packageRoot, SkillSourceDescriptor source) {
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        String logicalRef = packageRoot.getFileName() == null
                ? "unknown"
                : packageRoot.getFileName().toString();
        try {
            Path normalizedRoot = packageRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
                return failure(source, logicalRef, "SKILL_PACKAGE_NOT_DIRECTORY", "skill package is not a directory");
            }
            Map<String, byte[]> files = new TreeMap<>();
            try (var paths = Files.walk(normalizedRoot, limits.maxDepth() + 1)) {
                for (Path path : paths.toList()) {
                    if (path.equals(normalizedRoot)) continue;
                    if (Files.isSymbolicLink(path)) {
                        return failure(source, logicalRef, "SKILL_SYMLINK_REJECTED", "symbolic links are not allowed");
                    }
                    Path relative = normalizedRoot.relativize(path);
                    if (relative.getNameCount() > limits.maxDepth()) {
                        return failure(
                                source,
                                logicalRef,
                                "SKILL_PATH_DEPTH_EXCEEDED",
                                "skill package path exceeds the maximum depth");
                    }
                    validateRelativePath(relative.toString().replace('\\', '/'));
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        return failure(
                                source,
                                logicalRef,
                                "SKILL_FILE_TYPE_REJECTED",
                                "non-regular package entries are not allowed");
                    }
                    if (files.size() >= limits.maxFiles()) {
                        return failure(source, logicalRef, "SKILL_FILE_COUNT_EXCEEDED", "skill has too many files");
                    }
                    long size = Files.size(path);
                    if (size > limits.maxFileBytes()) {
                        return failure(source, logicalRef, "SKILL_FILE_SIZE_EXCEEDED", "skill file is too large");
                    }
                    files.put(relative.toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
            return parseFiles(logicalRef, files, source);
        } catch (IOException | IllegalArgumentException exception) {
            diagnostics.add(diagnostic(
                    source,
                    Optional.empty(),
                    Optional.of(logicalRef),
                    "SKILL_PACKAGE_READ_FAILED",
                    SkillDiagnosticSeverity.ERROR,
                    bounded(exception.getMessage(), "skill package could not be read")));
            return new SkillPackageParseResult(Optional.empty(), diagnostics);
        }
    }

    public SkillPackageParseResult parseFiles(
            String expectedDirectoryName, Map<String, byte[]> packageFiles, SkillSourceDescriptor source) {
        List<SkillDiagnostic> diagnostics = new ArrayList<>();
        Optional<SkillName> parsedName = Optional.empty();
        try {
            if (packageFiles.size() > limits.maxFiles()) {
                return failure(source, expectedDirectoryName, "SKILL_FILE_COUNT_EXCEEDED", "skill has too many files");
            }
            Map<String, byte[]> files = new TreeMap<>();
            long totalBytes = 0;
            for (var entry : packageFiles.entrySet()) {
                String path = entry.getKey().replace('\\', '/');
                validateRelativePath(path);
                if (path.split("/").length > limits.maxDepth()) {
                    return failure(
                            source,
                            expectedDirectoryName,
                            "SKILL_PATH_DEPTH_EXCEEDED",
                            "skill package path exceeds the maximum depth");
                }
                byte[] bytes = entry.getValue().clone();
                if (bytes.length > limits.maxFileBytes()) {
                    return failure(
                            source, expectedDirectoryName, "SKILL_FILE_SIZE_EXCEEDED", "skill file is too large");
                }
                totalBytes += bytes.length;
                if (totalBytes > limits.maxPackageBytes()) {
                    return failure(
                            source, expectedDirectoryName, "SKILL_PACKAGE_SIZE_EXCEEDED", "skill package is too large");
                }
                if (files.putIfAbsent(path, bytes) != null) {
                    return failure(source, expectedDirectoryName, "SKILL_DUPLICATE_PATH", "duplicate package path");
                }
            }
            byte[] skillBytes = files.get("SKILL.md");
            if (skillBytes == null) {
                return failure(source, expectedDirectoryName, "SKILL_ENTRYPOINT_MISSING", "SKILL.md is required");
            }
            if (skillBytes.length > limits.maxSkillBytes()) {
                return failure(
                        source, expectedDirectoryName, "SKILL_INSTRUCTION_SIZE_EXCEEDED", "SKILL.md is too large");
            }
            String skillText = decodeUtf8(skillBytes);
            int lines = skillText.split("\\R", -1).length;
            if (lines > limits.maxSkillLines()) {
                return failure(
                        source,
                        expectedDirectoryName,
                        "SKILL_INSTRUCTION_LINES_EXCEEDED",
                        "SKILL.md has too many lines");
            }
            FrontMatter frontMatter = frontMatter(skillText);
            JsonNode root = parseYaml(frontMatter.yaml());
            SkillName name = new SkillName(requiredText(root, "name"));
            parsedName = Optional.of(name);
            if (!name.value().equals(expectedDirectoryName)) {
                SkillDiagnosticSeverity severity = source.parserMode() == SkillParserMode.STRICT
                        ? SkillDiagnosticSeverity.ERROR
                        : SkillDiagnosticSeverity.WARNING;
                diagnostics.add(diagnostic(
                        source,
                        parsedName,
                        Optional.of("SKILL.md"),
                        "SKILL_DIRECTORY_NAME_MISMATCH",
                        severity,
                        "skill name does not match its package directory"));
                if (severity == SkillDiagnosticSeverity.ERROR) {
                    return new SkillPackageParseResult(Optional.empty(), diagnostics);
                }
            }
            String description = requiredText(root, "description");
            Optional<String> license = optionalText(root, "license");
            Optional<String> compatibility = optionalText(root, "compatibility");
            Map<String, String> metadata = metadata(root.get("metadata"), source, parsedName, diagnostics);
            Optional<SkillDeclaredVersion> version = Optional.ofNullable(metadata.get("haifa.version"))
                    .or(() -> Optional.ofNullable(metadata.get("version")))
                    .map(SkillDeclaredVersion::new);
            Set<ToolAlias> toolHints = toolHints(root.get("allowed-tools"), source, parsedName, diagnostics);
            for (var fields = root.fieldNames(); fields.hasNext(); ) {
                String field = fields.next();
                if (!FRONT_MATTER_FIELDS.contains(field)) {
                    diagnostics.add(diagnostic(
                            source,
                            parsedName,
                            Optional.of("SKILL.md"),
                            "SKILL_UNKNOWN_FRONT_MATTER_FIELD",
                            SkillDiagnosticSeverity.WARNING,
                            "unknown front matter field was ignored: " + bounded(field, "unknown")));
                }
            }
            String instructions = frontMatter.body().trim();
            if (instructions.isEmpty()) throw new IllegalArgumentException("SKILL.md body must not be blank");
            int estimatedTokens = estimateTokens(instructions);
            if (estimatedTokens > limits.maxEstimatedTokens()) {
                return failure(
                        source,
                        expectedDirectoryName,
                        "SKILL_TOKEN_BUDGET_EXCEEDED",
                        "SKILL.md exceeds the instruction token budget");
            }

            List<SkillResourceRef> resources = new ArrayList<>();
            Map<String, String> readable = new LinkedHashMap<>();
            boolean requiresReview = false;
            for (var entry : files.entrySet()) {
                String path = entry.getKey();
                byte[] bytes = entry.getValue();
                SkillResourceKind kind = kind(path);
                boolean readableText = readableText(path);
                if (kind == SkillResourceKind.SCRIPT) {
                    requiresReview = true;
                    diagnostics.add(diagnostic(
                            source,
                            parsedName,
                            Optional.of(path),
                            "SKILL_SCRIPT_REVIEW_REQUIRED",
                            SkillDiagnosticSeverity.WARNING,
                            "script content is indexed but cannot be executed"));
                }
                String mediaType = mediaType(path, readableText);
                resources.add(new SkillResourceRef(
                        path, kind, mediaType, SkillDigests.sha256(bytes), bytes.length, readableText));
                if (readableText && !path.equals("SKILL.md")) {
                    readable.put(path, decodeUtf8(bytes));
                }
            }
            SkillContentDigest packageDigest = packageDigest(files);
            SkillMetadata skillMetadata =
                    new SkillMetadata(name, description, version, license, compatibility, metadata, toolHints);
            SkillPackageIndex index = new SkillPackageIndex(packageDigest, resources);
            ParsedSkillPackage parsed =
                    new ParsedSkillPackage(skillMetadata, index, instructions, readable, requiresReview, diagnostics);
            return new SkillPackageParseResult(Optional.of(parsed), diagnostics);
        } catch (IOException | IllegalArgumentException exception) {
            diagnostics.add(diagnostic(
                    source,
                    parsedName,
                    Optional.of("SKILL.md"),
                    "SKILL_PARSE_FAILED",
                    SkillDiagnosticSeverity.ERROR,
                    bounded(exception.getMessage(), "skill package is invalid")));
            return new SkillPackageParseResult(Optional.empty(), diagnostics);
        }
    }

    private JsonNode parseYaml(String frontMatter) throws IOException {
        if (frontMatter.getBytes(StandardCharsets.UTF_8).length > MAX_FRONT_MATTER_BYTES) {
            throw new IllegalArgumentException("front matter is too large");
        }
        if (frontMatter.contains("!!")
                || frontMatter.contains("!<")
                || frontMatter.matches("(?s).*(^|\\s)[&*][A-Za-z0-9_-]+.*")) {
            throw new IllegalArgumentException("YAML tags and aliases are not supported");
        }
        JsonNode root = yaml.readTree(frontMatter);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("front matter must be an object");
        return root;
    }

    private static FrontMatter frontMatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md must start with YAML front matter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("SKILL.md front matter is not closed");
        return new FrontMatter(normalized.substring(4, end), normalized.substring(end + 5));
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.textValue().trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required and must be text");
        }
        return node.textValue();
    }

    private static Optional<String> optionalText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return Optional.empty();
        if (!node.isTextual() || node.textValue().trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty text");
        }
        return Optional.of(node.textValue());
    }

    private static Map<String, String> metadata(
            JsonNode node, SkillSourceDescriptor source, Optional<SkillName> name, List<SkillDiagnostic> diagnostics) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("metadata must be an object");
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                values.put(entry.getKey(), entry.getValue().asText());
            } else if (source.parserMode() == SkillParserMode.STRICT) {
                throw new IllegalArgumentException("metadata values must be scalar");
            } else {
                diagnostics.add(diagnostic(
                        source,
                        name,
                        Optional.of("SKILL.md"),
                        "SKILL_METADATA_VALUE_IGNORED",
                        SkillDiagnosticSeverity.WARNING,
                        "non-scalar metadata value was ignored"));
            }
        });
        return values;
    }

    private static Set<ToolAlias> toolHints(
            JsonNode node, SkillSourceDescriptor source, Optional<SkillName> name, List<SkillDiagnostic> diagnostics) {
        if (node == null || node.isNull()) return Set.of();
        if (!node.isTextual()) throw new IllegalArgumentException("allowed-tools must be a space-separated string");
        Set<ToolAlias> result = new LinkedHashSet<>();
        for (String token : node.textValue().trim().split("\\s+")) {
            if (token.isBlank()) continue;
            try {
                result.add(new ToolAlias(token));
            } catch (IllegalArgumentException exception) {
                diagnostics.add(diagnostic(
                        source,
                        name,
                        Optional.of("SKILL.md"),
                        "SKILL_TOOL_HINT_INCOMPATIBLE",
                        SkillDiagnosticSeverity.WARNING,
                        "an external allowed-tools token was not mapped to a Haifa Tool alias"));
            }
        }
        return result;
    }

    private static SkillContentDigest packageDigest(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(entry.getValue().length)
                        .array());
                digest.update(entry.getValue());
            });
            return new SkillContentDigest("sha256:" + java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("skill text is not valid UTF-8", exception);
        }
    }

    private static void validateRelativePath(String path) {
        if (path.isBlank()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*")
                || path.contains(":")
                || path.split("/").length > 32) {
            throw new IllegalArgumentException("invalid package-relative path");
        }
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid package-relative path");
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(".env")
                || lower.contains("/.env")
                || lower.startsWith(".git/")
                || lower.contains("/.git/")
                || lower.startsWith("target/")
                || lower.contains("/target/")
                || lower.startsWith("node_modules/")
                || lower.contains("/node_modules/")) {
            throw new IllegalArgumentException("secret candidates and build/cache directories are not allowed");
        }
    }

    private static SkillResourceKind kind(String path) {
        if (path.equals("SKILL.md")) return SkillResourceKind.INSTRUCTION;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("references/")) return SkillResourceKind.REFERENCE;
        if (lower.startsWith("assets/")) return SkillResourceKind.ASSET;
        if (lower.startsWith("scripts/")) return SkillResourceKind.SCRIPT;
        if (lower.startsWith("examples/")) return SkillResourceKind.EXAMPLE;
        if (lower.startsWith("tests/")) return SkillResourceKind.TEST;
        if (lower.startsWith("license") || lower.contains("/license")) return SkillResourceKind.LICENSE;
        return SkillResourceKind.OTHER;
    }

    private static boolean readableText(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return path.toLowerCase(Locale.ROOT).contains("license");
        return READABLE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String mediaType(String path, boolean readable) {
        if (!readable) return "application/octet-stream";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }

    private static int estimateTokens(String text) {
        return Math.max(1, (text.length() + 3) / 4);
    }

    private static SkillPackageParseResult failure(
            SkillSourceDescriptor source, String logicalRef, String code, String message) {
        SkillDiagnostic diagnostic = diagnostic(
                source,
                Optional.empty(),
                Optional.ofNullable(logicalRef),
                code,
                SkillDiagnosticSeverity.ERROR,
                message);
        return new SkillPackageParseResult(Optional.empty(), List.of(diagnostic));
    }

    private static SkillDiagnostic diagnostic(
            SkillSourceDescriptor source,
            Optional<SkillName> skill,
            Optional<String> path,
            String code,
            SkillDiagnosticSeverity severity,
            String message) {
        return new SkillDiagnostic(code, severity, source.reference(), skill, path, message);
    }

    private static String bounded(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String sanitized = value.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 400 ? sanitized : sanitized.substring(0, 400);
    }

    private record FrontMatter(String yaml, String body) {}
}
