package sample;

public final class RetryPolicyTest {
    public static void main(String[] args) {
        if (!RetryPolicy.shouldRetry(1, 3)) throw new AssertionError("early attempt rejected");
        if (RetryPolicy.shouldRetry(3, 3)) throw new AssertionError("last attempt should not retry");
    }
}
