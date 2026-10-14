package io.haifa.agent.starter;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures the declared outputs of one trusted Skill script invocation from {@code outputs/}
 * below the Workspace root, publishes them through the product's existing Artifact service and
 * removes the staged files afterwards. Run isolation comes from clearing each declared output
 * before the process starts: a previous Run can never be captured again.
 */
final class SkillScriptOutputsPublisher implements ToolProvider {
    static final String ARTIFACT_TYPE = "skill-script-output";
    static final String EXPORT_POLICY = "trusted-skill-script-outputs-v1";
    static final String OUTPUTS_DIRECTORY = "outputs";

    private final ToolProvider delegate;
    private final SkillScriptPlatformWorkspaceAccess access;
    private final Map<String, List<SkillScriptPlatforms.SkillScriptOutput>> outputs;
    private final ArtifactService artifacts;
    private final ProjectRef project;
    private final String workspaceRef;

    SkillScriptOutputsPublisher(
            ToolProvider delegate,
            SkillScriptPlatformWorkspaceAccess access,
            Map<String, List<SkillScriptPlatforms.SkillScriptOutput>> outputs,
            ArtifactService artifacts,
            ProjectRef project,
            String workspaceRef) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.outputs = Map.copyOf(outputs);
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.workspaceRef = Objects.requireNonNull(workspaceRef, "workspaceRef must not be null");
    }

    @Override
    public ToolProviderId id() {
        return delegate.id();
    }

    @Override
    public io.haifa.agent.core.tool.ToolResult invoke(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        String toolName = invocation.binding().definition().name().value();
        List<SkillScriptPlatforms.SkillScriptOutput> declared = outputs.getOrDefault(toolName, List.of());
        Path workspaceRoot = access.workspaceRoot();
        for (SkillScriptPlatforms.SkillScriptOutput output : declared) {
            deleteQuietly(workspaceRoot.resolve(OUTPUTS_DIRECTORY).resolve(output.name()));
        }
        ToolResult inner = delegate.invoke(invocation);
        if (!inner.successful() || declared.isEmpty()) {
            return inner;
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>(inner.structuredData());
        List<io.haifa.agent.core.reference.ArtifactRef> artifactRefs = new ArrayList<>(inner.artifacts());
        List<Map<String, Object>> published = new ArrayList<>();
        for (SkillScriptPlatforms.SkillScriptOutput output : declared) {
            Path file = workspaceRoot.resolve(OUTPUTS_DIRECTORY).resolve(output.name());
            byte[] bytes = readCapturedFile(file);
            String sha256 = "sha256:" + sha256Hex(bytes);
            Artifact artifact = artifacts.publish(
                    new ArtifactType(ARTIFACT_TYPE),
                    output.name(),
                    bytes,
                    output.mediaType(),
                    provenance(invocation, output, sha256));
            artifactRefs.add(artifact.reference());
            published.add(Map.of(
                    "name",
                    output.name(),
                    "artifactId",
                    artifact.id().value(),
                    "artifactType",
                    ARTIFACT_TYPE,
                    "mediaType",
                    output.mediaType(),
                    "sha256",
                    sha256,
                    "byteCount",
                    bytes.length));
            deleteQuietly(file);
        }
        data.put("outputs", List.copyOf(published));
        return new io.haifa.agent.core.tool.ToolResult(
                inner.successful(),
                inner.summary(),
                Map.copyOf(data),
                inner.assets(),
                List.copyOf(artifactRefs),
                inner.truncated());
    }

    private ArtifactProvenance provenance(
            ToolInvocationRequest invocation, SkillScriptPlatforms.SkillScriptOutput output, String sha256) {
        return new ArtifactProvenance(
                project,
                workspaceRef,
                invocation.runId(),
                invocation.sessionId().orElse(null),
                null,
                invocation.toolCallId().value(),
                OUTPUTS_DIRECTORY + "/" + output.name(),
                sha256,
                EXPORT_POLICY,
                invocation.principal());
    }

    private static byte[] readCapturedFile(Path file) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("declared trusted script output was not produced as a regular file");
        }
        try {
            return Files.readAllBytes(file);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("declared trusted script output could not be read", exception);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException ignored) {
            // Clearing is best-effort; the next invocation re-clears before capture.
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
