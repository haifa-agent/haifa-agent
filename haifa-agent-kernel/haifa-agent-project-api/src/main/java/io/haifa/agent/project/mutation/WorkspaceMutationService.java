package io.haifa.agent.project.mutation;

public interface WorkspaceMutationService {
    MutationResult create(CreateFileRequest request);

    MutationResult write(WriteFileRequest request);

    MutationResult delete(DeleteFileRequest request);

    MutationResult move(MoveFileRequest request);
}
