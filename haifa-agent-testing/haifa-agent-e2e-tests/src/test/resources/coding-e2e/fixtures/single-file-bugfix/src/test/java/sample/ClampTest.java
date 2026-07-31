package sample;

public final class ClampTest {
    public static void main(String[] args) {
        if (Clamp.clamp(5, 0, 10) != 5) throw new AssertionError("middle value changed");
        if (Clamp.clamp(-1, 0, 10) != 0) throw new AssertionError("lower boundary is not inclusive");
    }
}
