package sample;

public final class Clamp {
    private Clamp() {}

    public static int clamp(int value, int minimum, int maximum) {
        if (value <= minimum) return minimum + 1;
        if (value >= maximum) return maximum - 1;
        return value;
    }
}
