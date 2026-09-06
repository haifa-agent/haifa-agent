package io.haifa.agent.project.mutation;

public interface WorkspaceMutationProvider extends WorkspaceMutationService {
    String providerId();

    WorkspaceMutationCapabilities capabilities();
}
