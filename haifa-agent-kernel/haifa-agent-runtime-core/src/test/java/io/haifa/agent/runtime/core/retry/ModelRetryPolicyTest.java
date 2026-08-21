package io.haifa.agent.runtime.core.retry;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import java.time.Duration;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ModelRetryPolicyTest {
    @Test
    void defaultsToTwoAttemptsButRespectsAnExplicitSingleAttemptPolicy() {
        assertThat(ModelRetryPolicy.defaults().policy().maxAttempts()).isEqualTo(2);
        assertThat(ModelRetryPolicy.none().policy().maxAttempts()).isEqualTo(1);
    }

    @Test
    void retriesOnlyFailuresThatAreSafeBeforeConsumableOutput() {
        ModelRetryPolicy policy = new ModelRetryPolicy(new RetryPolicy(3, ignored -> true, BackoffStrategy.none()));

        EnumSet.of(
                        ModelErrorCategory.EMPTY_RESPONSE,
                        ModelErrorCategory.RATE_LIMITED,
                        ModelErrorCategory.TIMEOUT,
                        ModelErrorCategory.PROVIDER_UNAVAILABLE,
                        ModelErrorCategory.SERVER_ERROR,
                        ModelErrorCategory.TRANSPORT_ERROR,
                        ModelErrorCategory.MALFORMED_RESPONSE)
                .forEach(category -> assertThat(policy.policy().retryable().test(failure(category, true, false, null)))
                        .as(category.name())
                        .isTrue());

        EnumSet.of(
                        ModelErrorCategory.AUTHENTICATION_FAILED,
                        ModelErrorCategory.PERMISSION_DENIED,
                        ModelErrorCategory.INVALID_REQUEST,
                        ModelErrorCategory.MODEL_NOT_FOUND,
                        ModelErrorCategory.CONTEXT_TOO_LONG,
                        ModelErrorCategory.CONTENT_REJECTED,
                        ModelErrorCategory.PARTIAL_RESPONSE,
                        ModelErrorCategory.CANCELLED,
                        ModelErrorCategory.UNKNOWN_PROVIDER_ERROR)
                .forEach(category -> assertThat(policy.policy().retryable().test(failure(category, true, false, null)))
                        .as(category.name())
                        .isFalse());

        assertThat(policy.policy().retryable().test(failure(ModelErrorCategory.TRANSPORT_ERROR, true, true, null)))
                .as("consumable output makes transport replay unsafe")
                .isFalse();
        assertThat(policy.policy().retryable().test(failure(ModelErrorCategory.EMPTY_RESPONSE, false, false, null)))
                .as("the adapter may explicitly reject replay")
                .isFalse();
    }

    @Test
    void malformedResponseRequiresAnExplicitAdapterRetryMarkerAndNoOutput() {
        ModelRetryPolicy policy = new ModelRetryPolicy(new RetryPolicy(3, ignored -> false, BackoffStrategy.none()));

        assertThat(policy.policy().retryable().test(failure(ModelErrorCategory.MALFORMED_RESPONSE, true, false, null)))
                .isTrue();
        assertThat(policy.policy().retryable().test(failure(ModelErrorCategory.MALFORMED_RESPONSE, false, false, null)))
                .isFalse();
        assertThat(policy.policy().retryable().test(failure(ModelErrorCategory.MALFORMED_RESPONSE, true, true, null)))
                .isFalse();
        assertThat(policy.policy().retryable().test(failure(ModelErrorCategory.EMPTY_RESPONSE, true, false, null)))
                .isTrue();
    }

    @Test
    void genericRuntimeFailuresRequireAnExplicitHostPredicate() {
        assertThat(ModelRetryPolicy.defaults().policy().retryable().test(new IllegalStateException("software failure")))
                .isFalse();
        ModelRetryPolicy custom = new ModelRetryPolicy(new RetryPolicy(2, ignored -> true, BackoffStrategy.none()));
        assertThat(custom.policy().retryable().test(new IllegalStateException("explicit transient failure")))
                .isTrue();
    }

    @Test
    void honorsRetryAfterWithinTheConfiguredMaximumDelay() {
        ModelRetryPolicy policy = new ModelRetryPolicy(
                new RetryPolicy(3, ignored -> true, ignored -> Duration.ofSeconds(2)), Duration.ofSeconds(8));

        assertThat(policy.delay(1, failure(ModelErrorCategory.RATE_LIMITED, true, false, Duration.ofSeconds(5))))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delay(1, failure(ModelErrorCategory.RATE_LIMITED, true, false, Duration.ofSeconds(20))))
                .isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delay(1, failure(ModelErrorCategory.SERVER_ERROR, true, false, null)))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void jitterRemainsNonNegativeAndInsideTheConfiguredMaximum() {
        RuntimeBackoffPolicy backoff =
                new RuntimeBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(3), 2.0d, 0.2d);

        for (int iteration = 0; iteration < 100; iteration++) {
            assertThat(backoff.delay(1)).isBetween(Duration.ofMillis(800), Duration.ofMillis(1_200));
            assertThat(backoff.delay(10)).isBetween(Duration.ofMillis(2_400), Duration.ofSeconds(3));
        }
    }

    private static ModelInvocationException failure(
            ModelErrorCategory category, boolean retryable, boolean outputObserved, Duration retryAfter) {
        return new ModelInvocationException(
                category,
                retryable,
                0,
                "safe_code",
                new ModelCallId("call-1"),
                "safe failure",
                null,
                retryAfter,
                outputObserved);
    }
}
