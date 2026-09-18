package io.haifa.agent.application.project.artifact;

import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.WorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class ArtifactExportService {
    private final WorkspaceStore workspaces;
    private final WorkspaceFileService files;
    private final ArtifactService artifacts;
    private final ArtifactExportPolicy policy;

    public ArtifactExportService(
            WorkspaceStore workspaces,
            WorkspaceFileService files,
            ArtifactService artifacts,
            ArtifactExportPolicy policy) {
        this.workspaces = Objects.requireNonNull(workspaces);
        this.files = Objects.requireNonNull(files);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.policy = Objects.requireNonNull(policy);
    }

    public ArtifactExportResult export(ArtifactExportRequest request) {
        if (!policy.authorize(request)) throw new SecurityException("artifact export denied");
        var workspace = workspaces
                .find(request.workspaceId())
                .filter(value ->
                        value.projectId().value().equals(request.project().projectId()))
                .orElseThrow(() -> new IllegalArgumentException("workspace is unavailable for project"));
        if (!workspace.revision().equals(request.expectedRevision())) {
            throw new IllegalStateException("workspace revision changed during export");
        }
        byte[] content;
        if (request.sourceKind() == ArtifactExportSourceKind.FILE) {
            WorkspacePath path = new WorkspacePath(request.workspaceId(), request.sourcePath());
            var metadata = files.stat(path, true);
            if (metadata.size() > request.maxBytes())
                throw new IllegalArgumentException("artifact source exceeds limit");
            String statHash =
                    metadata.contentHash().orElseThrow(() -> new IllegalStateException("source hash unavailable"));
            if (!statHash.equals(request.expectedSourceHash())) {
                throw new IllegalStateException("artifact source changed before export");
            }
            var read = files.read(
                    path,
                    new ReadOptions(
                            request.maxBytes(),
                            Math.toIntExact(Math.min(Integer.MAX_VALUE, request.maxBytes())),
                            StandardCharsets.UTF_8,
                            false));
            content = read.text().getBytes(read.charset());
            if (read.truncated() || !read.contentHash().equals(request.expectedSourceHash())) {
                throw new IllegalStateException("artifact source changed while exporting");
            }
        } else {
            content = request.suppliedContent();
            if (content.length > request.maxBytes())
                throw new IllegalArgumentException("artifact source exceeds limit");
            if (!digest(content).equals(request.expectedSourceHash())) {
                throw new IllegalArgumentException("supplied document hash does not match request");
            }
        }
        var provenance = new ArtifactProvenance(
                request.project(),
                request.workspaceId().value(),
                request.runId(),
                request.sessionId(),
                request.fileChangeSetRef(),
                request.executionRef(),
                request.sourcePath().value(),
                request.expectedSourceHash(),
                request.exportPolicy(),
                request.actor());
        var artifact =
                artifacts.publish(request.artifactType(), request.title(), content, request.mediaType(), provenance);
        return new ArtifactExportResult(artifact.reference(), request.expectedSourceHash(), content.length);
    }

    private static String digest(byte[] content) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
