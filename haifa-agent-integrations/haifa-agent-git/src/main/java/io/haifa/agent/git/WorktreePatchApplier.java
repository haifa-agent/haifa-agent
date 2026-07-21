package io.haifa.agent.git;

import io.haifa.agent.project.patch.PatchApplyRequest;
import io.haifa.agent.project.patch.PatchApplyResult;

@FunctionalInterface
public interface WorktreePatchApplier {
    PatchApplyResult apply(PatchApplyRequest request);
}
