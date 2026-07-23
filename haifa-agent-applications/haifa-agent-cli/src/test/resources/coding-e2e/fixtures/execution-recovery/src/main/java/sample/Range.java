package sample;

public record Range(int minimum, int maximum) {
    public Range {
        if (minimum > maximum) throw new IllegalArgumentException("minimum exceeds maximum");
    }

    public boolean contains(int value) {
        return value > minimum && value < maximum;
    }
}
