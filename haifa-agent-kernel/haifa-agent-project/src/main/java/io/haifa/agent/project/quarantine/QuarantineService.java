package io.haifa.agent.project.quarantine;

import io.haifa.agent.project.mutation.MutationResult;

public interface QuarantineService {
    MutationResult restore(QuarantineRestoreRequest request);
}
