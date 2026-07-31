package sample;

public final class RetryPolicy {
    private RetryPolicy() {}

    public static boolean shouldRetry(int attempt, int maxAttempts) {
        return attempt <= maxAttempts;
    }
}
