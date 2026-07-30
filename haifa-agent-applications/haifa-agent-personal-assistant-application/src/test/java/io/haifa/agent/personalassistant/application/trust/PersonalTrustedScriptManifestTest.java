package io.haifa.agent.personalassistant.application.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.skill.api.SkillTrustGrantState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalTrustedScriptManifestTest {
    @TempDir
    Path temporary;

    @Test
    void loadsStrictDigestPinnedManifestWithoutTrustingItsPhysicalPath() throws Exception {
        Path manifest = temporary.resolve("trust.yml");
        Files.writeString(manifest, document(""));

        PersonalTrustedScriptManifest loaded = PersonalTrustedScriptManifest.load(Optional.of(manifest));

        assertThat(loaded.digest()).startsWith("sha256:");
        assertThat(loaded.packages()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("package-review");
            assertThat(entry.state()).isEqualTo(SkillTrustGrantState.ACTIVE);
        });
        assertThat(loaded.scripts()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("script-review");
            assertThat(entry.state()).isEqualTo(SkillTrustGrantState.REVOKED);
            assertThat(entry.expectedToolDefinitionHash()).isEqualTo("0".repeat(64));
        });
    }

    @Test
    void unknownFieldsMalformedDigestsAndMissingFilesFailClosed() throws Exception {
        Path unknown = temporary.resolve("unknown.yml");
        Files.writeString(unknown, document("unexpected: true\n"));
        assertThatThrownBy(() -> PersonalTrustedScriptManifest.load(Optional.of(unknown)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be read");

        Path malformed = temporary.resolve("malformed.yml");
        Files.writeString(malformed, document("").replace("sha256:" + "a".repeat(64), "not-a-digest"));
        assertThatThrownBy(() -> PersonalTrustedScriptManifest.load(Optional.of(malformed)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PersonalTrustedScriptManifest.load(Optional.of(temporary.resolve("missing.yml"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    private static String document(String extraRootField) {
        return """
                schemaVersion: 1
                %spackages:
                  - id: package-review
                    version: 1
                    skillAlias: shared-skill
                    registrationDigest: sha256:%s
                    packageDigest: sha256:%s
                    scope: PRODUCT
                    state: ACTIVE
                    issuedAt: 2026-07-30T00:00:00Z
                    expiresAt: 2027-07-30T00:00:00Z
                    reviewerRef: operator
                    reviewSourceRef: review-ticket
                scripts:
                  - id: script-review
                    version: 1
                    packageReviewGrantId: package-review
                    capability: dcf_validate
                    scriptRelativePath: scripts/transform
                    scriptDigest: sha256:%s
                    expectedToolDefinitionHash: %s
                    runtimeRef: runtime
                    executionConfigurationDigest: %s
                    sandboxDigest: sha256:%s
                    capabilities: [execution.run]
                    networkHosts: []
                    scope: PRODUCT
                    state: REVOKED
                    issuedAt: 2026-07-30T00:00:00Z
                    revokedAt: 2026-07-30T00:01:00Z
                    reviewerRef: operator
                    reviewSourceRef: review-ticket
                """
                .formatted(
                        extraRootField,
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        "0".repeat(64),
                        "d".repeat(64),
                        "e".repeat(64));
    }
}
