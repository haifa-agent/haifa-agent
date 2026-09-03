package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.git.GitRepositoryRef;

@FunctionalInterface
public interface RepositoryBaselineCapture {
    RepositoryBaseline capture(RepositoryRunContext context, GitRepositoryRef repository);
}
