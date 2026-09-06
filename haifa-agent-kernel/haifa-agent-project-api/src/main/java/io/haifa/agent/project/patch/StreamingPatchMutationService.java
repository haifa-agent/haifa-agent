package io.haifa.agent.project.patch;

import io.haifa.agent.project.mutation.MutationResult;
import io.haifa.agent.project.mutation.WorkspaceMutationService;

/** Provider extension for applying an existing-file patch without materializing the complete file in memory. */
public interface StreamingPatchMutationService extends WorkspaceMutationService {
    MutationResult patch(PatchFileMutationRequest request);
}
