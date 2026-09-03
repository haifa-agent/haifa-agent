package io.haifa.agent.git;

import io.haifa.agent.execution.api.ExecutionRequest;

/** Creates or resolves the exact public policy decision bound to an internal Git read request. */
@FunctionalInterface
public interface GitExecutionAuthorizer {
    String authorize(ExecutionRequest plannedRequest);

    static GitExecutionAuthorizer existingDecision() {
        return request -> request.context().policyDecisionRef();
    }
}
