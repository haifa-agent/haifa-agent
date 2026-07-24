package io.haifa.agent.skill.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillPackageParserTest {
    private final SkillPackageParser parser = new SkillPackageParser(SkillPackageLimits.defaults());

    @Test
    void parsesPortablePackageAndIndexesScriptsWithoutExecutingThem() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
                "SKILL.md",
                bytes(
                        """
                        ---
                        name: sample-skill
                        description: Explains how to complete a sample task. Use for sample tasks.
                        license: Apache-2.0
                        metadata:
                          haifa.version: 1.0.0
                        allowed-tools: file_read
                        ---

                        # Sample

                        Follow the reviewed procedure.
                        """));
        files.put("references/checklist.md", bytes("# Checklist"));
        files.put("scripts/run.sh", bytes("echo never-executed"));

        var result = parser.parseFiles("sample-skill", files, descriptor(SkillParserMode.STRICT));

        assertThat(result.parsed()).isPresent();
        assertThat(result.parsed().orElseThrow().requiresReview()).isTrue();
        assertThat(result.parsed().orElseThrow().metadata().toolHints())
                .extracting(value -> value.value())
                .containsExactly("file_read");
        assertThat(result.parsed().orElseThrow().readableResources())
                .containsKeys("references/checklist.md", "scripts/run.sh");
        assertThat(result.diagnostics()).extracting(value -> value.code()).contains("SKILL_SCRIPT_REVIEW_REQUIRED");
    }

    @Test
    void rejectsUnsafeYamlPathsAndOversizedInstructions() {
        var yaml = parser.parseFiles(
                "bad",
                Map.of(
                        "SKILL.md",
                        bytes(
                                """
                                ---
                                name: bad
                                description: &shared bad
                                metadata:
                                  value: *shared
                                ---
                                body
                                """)),
                descriptor(SkillParserMode.STRICT));
        assertThat(yaml.parsed()).isEmpty();

        var path = parser.parseFiles(
                "bad",
                Map.of(
                        "SKILL.md",
                        bytes(
                                """
                                ---
                                name: bad
                                description: A valid description for a bad package.
                                ---
                                body
                                """),
                        "../escape.md",
                        bytes("escape")),
                descriptor(SkillParserMode.STRICT));
        assertThat(path.parsed()).isEmpty();
    }

    @Test
    void strictRejectsAndCompatibleWarnsOnDirectoryMismatch() {
        byte[] skill = bytes(
                """
                ---
                name: actual-name
                description: Describes a portable test Skill. Use for compatibility testing.
                ---
                body
                """);
        assertThat(parser.parseFiles("different-name", Map.of("SKILL.md", skill), descriptor(SkillParserMode.STRICT))
                        .parsed())
                .isEmpty();
        var compatible =
                parser.parseFiles("different-name", Map.of("SKILL.md", skill), descriptor(SkillParserMode.COMPATIBLE));
        assertThat(compatible.parsed()).isPresent();
        assertThat(compatible.diagnostics())
                .extracting(value -> value.code())
                .contains("SKILL_DIRECTORY_NAME_MISMATCH");
    }

    @Test
    void rejectsDuplicateMissingAndMalformedFrontMatter() {
        var duplicate = parser.parseFiles(
                "sample-skill",
                Map.of(
                        "SKILL.md",
                        bytes(
                                """
                                ---
                                name: sample-skill
                                name: duplicate
                                description: A valid description for duplicate field testing.
                                ---
                                body
                                """)),
                descriptor(SkillParserMode.STRICT));
        var missing = parser.parseFiles(
                "sample-skill",
                Map.of(
                        "SKILL.md",
                        bytes(
                                """
                                ---
                                name: sample-skill
                                ---
                                body
                                """)),
                descriptor(SkillParserMode.STRICT));
        var malformed = parser.parseFiles(
                "sample-skill",
                Map.of("SKILL.md", bytes("---\nname: [\n---\nbody")),
                descriptor(SkillParserMode.STRICT));

        assertThat(duplicate.parsed()).isEmpty();
        assertThat(missing.parsed()).isEmpty();
        assertThat(malformed.parsed()).isEmpty();
    }

    @Test
    void compatibleModeIgnoresNonScalarMetadataWhileStrictModeRejectsIt() {
        byte[] skill = bytes(
                """
                ---
                name: sample-skill
                description: Exercises all portable front matter and compatibility behavior.
                license: Apache-2.0
                compatibility: Java 21
                metadata:
                  nested:
                    value: ignored
                unknown-extension: preserved-as-diagnostic
                ---
                body
                """);

        assertThat(parser.parseFiles("sample-skill", Map.of("SKILL.md", skill), descriptor(SkillParserMode.STRICT))
                        .parsed())
                .isEmpty();
        var compatible =
                parser.parseFiles("sample-skill", Map.of("SKILL.md", skill), descriptor(SkillParserMode.COMPATIBLE));

        assertThat(compatible.parsed()).isPresent();
        assertThat(compatible.diagnostics())
                .extracting(value -> value.code())
                .contains("SKILL_METADATA_VALUE_IGNORED", "SKILL_UNKNOWN_FRONT_MATTER_FIELD");
        assertThat(compatible.parsed().orElseThrow().metadata().license()).contains("Apache-2.0");
        assertThat(compatible.parsed().orElseThrow().metadata().compatibility()).contains("Java 21");
    }

    @Test
    void rejectsInvalidUtf8UnsafePlatformPathsAndSecretCandidates() {
        byte[] invalidUtf8 = {(byte) 0xC3, (byte) 0x28};
        assertThat(parser.parseFiles(
                                "sample-skill", Map.of("SKILL.md", invalidUtf8), descriptor(SkillParserMode.STRICT))
                        .parsed())
                .isEmpty();

        byte[] skill = minimalSkill("sample-skill");
        for (String path : new String[] {
            "../escape.md", "/absolute.md", "\\\\server\\share.md", "C:\\device.md", "file.txt:stream", ".env"
        }) {
            assertThat(parser.parseFiles(
                                    "sample-skill",
                                    Map.of("SKILL.md", skill, path, bytes("unsafe")),
                                    descriptor(SkillParserMode.STRICT))
                            .parsed())
                    .as(path)
                    .isEmpty();
        }
    }

    @Test
    void enforcesFileCountDepthSizePackageAndTokenBudgets() {
        var limited = new SkillPackageParser(new SkillPackageLimits(3, 2, 256, 512, 256, 20, 20));
        byte[] skill = minimalSkill("sample-skill");

        assertThat(limited.parseFiles(
                                "sample-skill",
                                Map.of(
                                        "SKILL.md", skill,
                                        "one.txt", bytes("1"),
                                        "two.txt", bytes("2"),
                                        "three.txt", bytes("3")),
                                descriptor(SkillParserMode.STRICT))
                        .diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("SKILL_FILE_COUNT_EXCEEDED");
        assertThat(limited.parseFiles(
                                "sample-skill",
                                Map.of("SKILL.md", skill, "one/two/three.txt", bytes("deep")),
                                descriptor(SkillParserMode.STRICT))
                        .diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("SKILL_PATH_DEPTH_EXCEEDED");
        assertThat(limited.parseFiles(
                                "sample-skill",
                                Map.of("SKILL.md", skill, "large.txt", new byte[257]),
                                descriptor(SkillParserMode.STRICT))
                        .diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("SKILL_FILE_SIZE_EXCEEDED");

        var packageLimited = new SkillPackageParser(new SkillPackageLimits(4, 2, 300, 512, 256, 20, 200));
        assertThat(packageLimited
                        .parseFiles(
                                "sample-skill",
                                Map.of(
                                        "SKILL.md", skill,
                                        "one.txt", new byte[200],
                                        "two.txt", new byte[200]),
                                descriptor(SkillParserMode.STRICT))
                        .diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("SKILL_PACKAGE_SIZE_EXCEEDED");

        var lineLimited = new SkillPackageParser(new SkillPackageLimits(3, 2, 512, 1024, 512, 5, 200));
        assertThat(lineLimited
                        .parseFiles("sample-skill", Map.of("SKILL.md", skill), descriptor(SkillParserMode.STRICT))
                        .diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("SKILL_INSTRUCTION_LINES_EXCEEDED");

        var tokenLimited = new SkillPackageParser(new SkillPackageLimits(3, 2, 512, 1024, 512, 20, 20));
        byte[] tokenHeavySkill = bytes(
                """
                ---
                name: sample-skill
                description: Exercises the bounded instruction token budget.
                ---
                %s
                """
                        .formatted("x".repeat(100)));
        assertThat(tokenLimited
                        .parseFiles(
                                "sample-skill", Map.of("SKILL.md", tokenHeavySkill), descriptor(SkillParserMode.STRICT))
                        .diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("SKILL_TOKEN_BUDGET_EXCEEDED");
    }

    @Test
    void packageDigestIsIndependentOfEnumerationOrderAndChangesWithContent() {
        byte[] skill = minimalSkill("sample-skill");
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        ordered.put("SKILL.md", skill);
        ordered.put("references/a.md", bytes("A"));
        Map<String, byte[]> reversed = new HashMap<>();
        reversed.put("references/a.md", bytes("A"));
        reversed.put("SKILL.md", skill);
        Map<String, byte[]> changed = new LinkedHashMap<>(ordered);
        changed.put("references/a.md", bytes("B"));

        var first = parser.parseFiles("sample-skill", ordered, descriptor(SkillParserMode.STRICT));
        var second = parser.parseFiles("sample-skill", reversed, descriptor(SkillParserMode.STRICT));
        var third = parser.parseFiles("sample-skill", changed, descriptor(SkillParserMode.STRICT));

        assertThat(first.parsed().orElseThrow().packageIndex().digest())
                .isEqualTo(second.parsed().orElseThrow().packageIndex().digest())
                .isNotEqualTo(third.parsed().orElseThrow().packageIndex().digest());
    }

    private static SkillSourceDescriptor descriptor(SkillParserMode mode) {
        return new SkillSourceDescriptor(
                new SkillSourceRef("test", "1"), SkillScopeRef.sdk(), SkillOrigin.BUNDLED, 0, mode, true, false);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] minimalSkill(String name) {
        return bytes(
                """
                ---
                name: %s
                description: A portable Skill used to exercise parser security limits.
                ---
                Follow the bounded procedure.
                """
                        .formatted(name));
    }
}
