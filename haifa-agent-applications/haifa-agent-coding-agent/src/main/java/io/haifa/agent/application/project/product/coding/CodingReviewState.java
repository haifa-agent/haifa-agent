package io.haifa.agent.application.project.product.coding;
/** Trusted product projection completeness; never an approval or permission grant. */
public record CodingReviewState(boolean targetComplete, boolean contentTruncated, boolean sessionConsentEligible) {}
