package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.path.WorkspacePath;

/** Run-scoped hook around an execution that may modify the authorized work directory. */
public interface ExecutionRepositoryBaselineObserver {
    void beforeDispatch(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir);

    void afterCompletion(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir);

    static ExecutionRepositoryBaselineObserver noop() {
        return new ExecutionRepositoryBaselineObserver() {
            @Override
            public void beforeDispatch(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {}

            @Override
            public void afterCompletion(TenantRef tenant, String runRef, PrincipalRef actor, WorkspacePath workdir) {}
        };
    }
}
