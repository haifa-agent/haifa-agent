package io.haifa.agent.personalassistant.application.trust;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillTrustDigests;
import io.haifa.agent.skill.api.SkillTrustGrantState;
import io.haifa.agent.skill.api.SkillTrustScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strict product-owned manifest; a Skill package cannot declare or modify this authority. */
public record PersonalTrustedScriptManifest(
        String digest, List<PackageReview> packages, List<ScriptExecution> scripts) {
    private static final int MAXIMUM_BYTES = 1024 * 1024;

    public PersonalTrustedScriptManifest {
        digest = requireDigest(digest, "digest");
        packages = List.copyOf(Objects.requireNonNull(packages, "packages must not be null"));
        scripts = List.copyOf(Objects.requireNonNull(scripts, "scripts must not be null"));
        if (packages.stream().map(PackageReview::id).distinct().count() != packages.size()) {
            throw new IllegalArgumentException("trusted script package grant ids must be unique");
        }
        if (scripts.stream().map(ScriptExecution::id).distinct().count() != scripts.size()) {
            throw new IllegalArgumentException("trusted script execution grant ids must be unique");
        }
        Set<String> packageIds =
                packages.stream().map(PackageReview::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (scripts.stream().anyMatch(script -> !packageIds.contains(script.packageReviewGrantId()))) {
            throw new IllegalArgumentException("script entry references an unknown package review grant");
        }
    }

    public static PersonalTrustedScriptManifest empty() {
        return new PersonalTrustedScriptManifest(
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", List.of(), List.of());
    }

    public static PersonalTrustedScriptManifest load(Optional<Path> configuredPath) {
        Objects.requireNonNull(configuredPath, "configuredPath must not be null");
        if (configuredPath.isEmpty()) return empty();
        Path path = configuredPath.orElseThrow().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("trusted script manifest is unavailable");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("trusted script manifest size is invalid");
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            ManifestDocument document = mapper.readValue(bytes, ManifestDocument.class);
            if (document.schemaVersion() != 1) {
                throw new IllegalArgumentException("unsupported trusted script manifest schemaVersion");
            }
            String digest = SkillTrustDigests.content(new String(bytes, StandardCharsets.UTF_8))
                    .value();
            return new PersonalTrustedScriptManifest(
                    digest,
                    document.packages() == null ? List.of() : document.packages(),
                    document.scripts() == null ? List.of() : document.scripts());
        } catch (IOException exception) {
            throw new IllegalArgumentException("trusted script manifest cannot be read", exception);
        }
    }

    public record ManifestDocument(int schemaVersion, List<PackageReview> packages, List<ScriptExecution> scripts) {}

    public record PackageReview(
            String id,
            long version,
            String skillAlias,
            String registrationDigest,
            String packageDigest,
            SkillTrustScope scope,
            SkillTrustGrantState state,
            String issuedAt,
            String expiresAt,
            String revokedAt,
            String reviewerRef,
            String reviewSourceRef) {
        public PackageReview {
            id = text(id, "package.id", 128);
            if (version < 1) throw new IllegalArgumentException("package.version must be positive");
            skillAlias = text(skillAlias, "package.skillAlias", 128);
            registrationDigest = requireDigest(registrationDigest, "package.registrationDigest");
            packageDigest = requireDigest(packageDigest, "package.packageDigest");
            scope = Objects.requireNonNull(scope, "package.scope must not be null");
            state = Objects.requireNonNull(state, "package.state must not be null");
            issuedAt = instant(issuedAt, "package.issuedAt").toString();
            expiresAt = optionalInstant(expiresAt, "package.expiresAt");
            revokedAt = optionalInstant(revokedAt, "package.revokedAt");
            reviewerRef = text(reviewerRef, "package.reviewerRef", 256);
            reviewSourceRef = text(reviewSourceRef, "package.reviewSourceRef", 256);
        }

        public SkillContentDigest registrationContentDigest() {
            return new SkillContentDigest(registrationDigest);
        }

        public SkillContentDigest packageContentDigest() {
            return new SkillContentDigest(packageDigest);
        }

        public Instant issuedInstant() {
            return Instant.parse(issuedAt);
        }

        public Optional<Instant> expiresInstant() {
            return expiresAt.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(expiresAt));
        }

        public Optional<Instant> revokedInstant() {
            return revokedAt.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(revokedAt));
        }
    }

    public record ScriptExecution(
            String id,
            long version,
            String packageReviewGrantId,
            String capability,
            String scriptRelativePath,
            String scriptDigest,
            String expectedToolDefinitionHash,
            String runtimeRef,
            String executionConfigurationDigest,
            String sandboxDigest,
            List<String> capabilities,
            List<String> networkHosts,
            SkillTrustScope scope,
            SkillTrustGrantState state,
            String issuedAt,
            String expiresAt,
            String revokedAt,
            String reviewerRef,
            String reviewSourceRef) {
        public ScriptExecution {
            id = text(id, "script.id", 128);
            if (version < 1) throw new IllegalArgumentException("script.version must be positive");
            packageReviewGrantId = text(packageReviewGrantId, "script.packageReviewGrantId", 128);
            capability = text(capability, "script.capability", 128);
            scriptRelativePath =
                    text(scriptRelativePath, "script.scriptRelativePath", 512).replace('\\', '/');
            if (scriptRelativePath.startsWith("/")
                    || scriptRelativePath.contains("../")
                    || scriptRelativePath.contains(":")
                    || scriptRelativePath.equals("..")) {
                throw new IllegalArgumentException("script.scriptRelativePath must be package-relative");
            }
            scriptDigest = requireDigest(scriptDigest, "script.scriptDigest");
            expectedToolDefinitionHash = text(expectedToolDefinitionHash, "script.expectedToolDefinitionHash", 64)
                    .toLowerCase(Locale.ROOT);
            if (!expectedToolDefinitionHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("script.expectedToolDefinitionHash is invalid");
            }
            runtimeRef = text(runtimeRef, "script.runtimeRef", 128);
            executionConfigurationDigest = text(executionConfigurationDigest, "script.executionConfigurationDigest", 64)
                    .toLowerCase(Locale.ROOT);
            if (!executionConfigurationDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("script.executionConfigurationDigest is invalid");
            }
            sandboxDigest = requireDigest(sandboxDigest, "script.sandboxDigest");
            capabilities = safeList(capabilities, "script.capabilities", 32);
            if (capabilities.isEmpty()) {
                throw new IllegalArgumentException("script.capabilities must not be empty");
            }
            networkHosts = safeList(networkHosts, "script.networkHosts", 32);
            scope = Objects.requireNonNull(scope, "script.scope must not be null");
            state = Objects.requireNonNull(state, "script.state must not be null");
            issuedAt = instant(issuedAt, "script.issuedAt").toString();
            expiresAt = optionalInstant(expiresAt, "script.expiresAt");
            revokedAt = optionalInstant(revokedAt, "script.revokedAt");
            reviewerRef = text(reviewerRef, "script.reviewerRef", 256);
            reviewSourceRef = text(reviewSourceRef, "script.reviewSourceRef", 256);
        }

        public SkillContentDigest scriptContentDigest() {
            return new SkillContentDigest(scriptDigest);
        }

        public Instant issuedInstant() {
            return Instant.parse(issuedAt);
        }

        public Optional<Instant> expiresInstant() {
            return expiresAt.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(expiresAt));
        }

        public Optional<Instant> revokedInstant() {
            return revokedAt.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(revokedAt));
        }
    }

    private static List<String> safeList(List<String> values, String field, int maximum) {
        List<String> result = (values == null ? List.<String>of() : values)
                .stream()
                        .map(value -> text(value, field, 256))
                        .sorted()
                        .distinct()
                        .toList();
        if (result.size() > maximum) throw new IllegalArgumentException(field + " exceeds maximum entries");
        return result;
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(text(value, field, 64));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", exception);
        }
    }

    private static String optionalInstant(String value, String field) {
        if (value == null || value.isBlank()) return "";
        return instant(value, field).toString();
    }

    private static String requireDigest(String value, String field) {
        String digest = text(value, field, 71).toLowerCase(Locale.ROOT);
        if (!digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
        return digest;
    }

    private static String text(String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
