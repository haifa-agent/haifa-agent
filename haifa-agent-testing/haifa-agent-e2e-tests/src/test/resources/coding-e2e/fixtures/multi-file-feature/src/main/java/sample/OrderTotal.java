package sample;

public final class OrderTotal {
    private OrderTotal() {}

    public static int totalAfterDiscount(int subtotal, DiscountPolicy policy) {
        return Math.max(0, subtotal - policy.discountFor(subtotal));
    }
}
