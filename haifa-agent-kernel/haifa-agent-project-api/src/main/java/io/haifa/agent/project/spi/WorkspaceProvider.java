package io.haifa.agent.project.spi;

import io.haifa.agent.project.filesystem.WorkspaceFileService;

public interface WorkspaceProvider extends WorkspaceFileService {
    String providerId();
}
