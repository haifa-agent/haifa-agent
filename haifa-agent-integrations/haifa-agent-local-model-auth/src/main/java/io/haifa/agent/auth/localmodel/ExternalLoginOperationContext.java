package io.haifa.agent.auth.localmodel;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/** Product-neutral resources for one login operation. */
public record ExternalLoginOperationContext(
        ExternalLoginAttemptId attemptId,
        Clock clock,
        BrowserLauncher browserLauncher,
        Consumer<URI> browserAuthorizationSink,
        Consumer<ExternalLoginAttemptSnapshot> progressSink) {
    public ExternalLoginOperationContext {
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        clock = Objects.requireNonNull(clock, "clock must not be null");
        browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher must not be null");
        browserAuthorizationSink =
                Objects.requireNonNull(browserAuthorizationSink, "browserAuthorizationSink must not be null");
        progressSink = Objects.requireNonNull(progressSink, "progressSink must not be null");
    }

    @FunctionalInterface
    public interface BrowserLauncher {
        boolean open(URI authorizationUri);
    }
}
