package io.haifa.agent.testing.delivery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Immutable public catalog entry for one generalized autonomous-delivery case. */
public record AutonomousDeliveryCase(
        String caseId,
        String caseVersion,
        String title,
        String language,
        String taskType,
        List<String> capabilities,
        List<String> riskDimensions,
        String promptResource,
        String promptSha256,
        String workspaceResource,
        String workspaceSha256,
        List<String> executableWorkspacePaths,
        String acceptanceResource,
        String acceptanceSha256,
        String oracleId,
        String graderId,
        String source,
        String license,
        String changeNote) {
    private static final Pattern CASE_ID = Pattern.compile("0[1-9]|10");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]*\\.[0-9]+\\.[0-9]+");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STABLE_TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public AutonomousDeliveryCase {
        if (!CASE_ID.matcher(requireText(caseId, "caseId")).matches()) {
            throw new IllegalArgumentException("caseId must be 01 through 10");
        }
        if (!VERSION.matcher(requireText(caseVersion, "caseVersion")).matches()) {
            throw new IllegalArgumentException("caseVersion must use semantic version syntax");
        }
        title = requireText(title, "title");
        language = stableToken(language, "language");
        taskType = stableToken(taskType, "taskType");
        capabilities = immutableTokens(capabilities, "capabilities");
        riskDimensions = immutableTokens(riskDimensions, "riskDimensions");
        promptResource = safeResource(promptResource, "promptResource");
        promptSha256 = digest(promptSha256, "promptSha256");
        workspaceResource = safeResource(workspaceResource, "workspaceResource");
        workspaceSha256 = digest(workspaceSha256, "workspaceSha256");
        executableWorkspacePaths = immutablePaths(executableWorkspacePaths, "executableWorkspacePaths");
        acceptanceResource = safeResource(acceptanceResource, "acceptanceResource");
        acceptanceSha256 = digest(acceptanceSha256, "acceptanceSha256");
        oracleId = requireText(oracleId, "oracleId");
        graderId = requireText(graderId, "graderId");
        source = stableToken(source, "source");
        license = stableToken(license, "license");
        changeNote = changeNote == null || changeNote.isBlank() ? null : changeNote.strip();
    }

    public Optional<String> optionalChangeNote() {
        return Optional.ofNullable(changeNote);
    }

    private static List<String> immutableTokens(List<String> values, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        copy.forEach(value -> stableToken(value, field));
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return copy;
    }

    private static List<String> immutablePaths(List<String> values, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        copy.forEach(value -> safeResource(value, field));
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return copy;
    }

    private static String safeResource(String value, String field) {
        String resource = requireText(value, field);
        if (resource.startsWith("/") || resource.contains("\\") || resource.contains("..") || resource.contains("//")) {
            throw new IllegalArgumentException(field + " must be a safe classpath-relative path");
        }
        return resource;
    }

    private static String stableToken(String value, String field) {
        String token = requireText(value, field);
        if (!STABLE_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException(field + " must be a stable uppercase token");
        }
        return token;
    }

    private static String digest(String value, String field) {
        String digest = requireText(value, field);
        if (!SHA_256.matcher(digest).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return digest;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
