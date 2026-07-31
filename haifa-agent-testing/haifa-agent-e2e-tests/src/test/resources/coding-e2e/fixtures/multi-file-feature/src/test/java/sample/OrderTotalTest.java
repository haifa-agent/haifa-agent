package sample;

public final class OrderTotalTest {
    public static void main(String[] args) {
        var policy = new ThresholdDiscountPolicy(100, 25);
        if (OrderTotal.totalAfterDiscount(99, policy) != 99) throw new AssertionError("discount below threshold");
        if (OrderTotal.totalAfterDiscount(100, policy) != 75) throw new AssertionError("discount missing");
    }
}
