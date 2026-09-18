package io.haifa.agent.application.project.artifact;

@FunctionalInterface
public interface ArtifactExportPolicy {
    boolean authorize(ArtifactExportRequest request);
}
