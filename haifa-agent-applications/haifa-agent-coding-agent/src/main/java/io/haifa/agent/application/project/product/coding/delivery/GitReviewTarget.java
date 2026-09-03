package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.git.GitRepositoryRef;
import java.util.Objects;

public record GitReviewTarget(GitRepositoryRef repository) implements ReviewTarget {
    public GitReviewTarget {
        repository = Objects.requireNonNull(repository, "repository must not be null");
    }
}
