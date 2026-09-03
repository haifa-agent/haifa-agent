package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.git.GitReviewResult;

@FunctionalInterface
public interface RepositoryReviewCapture {
    GitReviewResult capture(String runRef, RepositoryBaseline baseline);
}
