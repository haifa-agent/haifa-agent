package io.haifa.agent.application.project.admin;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.domain.Project;

@FunctionalInterface
public interface AdminAuthorizer {
    boolean canRead(PrincipalRef actor, Project project);
}
