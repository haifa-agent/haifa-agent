package io.haifa.agent.application.project.product.coding.delivery;

/** Run-local review routing target. */
public sealed interface ReviewTarget permits GitReviewTarget, PlainReviewTarget {}
