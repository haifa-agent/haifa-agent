package io.haifa.agent.application.project.product.coding.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Safe provider projection for the Coding connection center. It never contains credentials. */
public record CodingAuthenticationProviderView(
        String providerId,
        String displayName,
        Category category,
        boolean connected,
        boolean recommended,
        boolean current,
        Optional<String> accountLabel,
        Optional<String> planLabel,
        List<CodingAuthenticationMethodView> methods,
        Optional<String> unavailableReason) {
    public CodingAuthenticationProviderView {
        providerId = text(providerId, "providerId");
        displayName = text(displayName, "displayName");
        category = Objects.requireNonNull(category, "category must not be null");
        accountLabel = Objects.requireNonNull(accountLabel, "accountLabel must not be null")
                .map(value -> text(value, "accountLabel"));
        planLabel = Objects.requireNonNull(planLabel, "planLabel must not be null")
                .map(value -> text(value, "planLabel"));
        methods = List.copyOf(Objects.requireNonNull(methods, "methods must not be null"));
        unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason must not be null")
                .map(value -> text(value, "unavailableReason"));
        if (methods.isEmpty() && unavailableReason.isEmpty()) {
            throw new IllegalArgumentException("provider without methods requires an unavailable reason");
        }
        if (!methods.isEmpty() && unavailableReason.isPresent()) {
            throw new IllegalArgumentException("provider with methods cannot have an unavailable reason");
        }
    }

    public enum Category {
        SUBSCRIPTION,
        API_KEY
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
