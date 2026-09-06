package io.haifa.agent.project.spi;

import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import java.util.Set;

public interface WorkspaceProvider extends WorkspaceFileService {
    String providerId();

    Set<WorkspaceBindingMode> supportedBindingModes();
}
