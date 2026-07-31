package sample;

public final class RangeTest {
    public static void main(String[] args) {
        var range = new Range(-2, 2);
        if (!range.contains(-2)) throw new AssertionError("minimum must be included");
        if (!range.contains(2)) throw new AssertionError("maximum must be included");
        if (range.contains(-3)) throw new AssertionError("outside value accepted");
    }
}
