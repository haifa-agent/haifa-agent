package sample;

public final class UsernameValidatorTest {
    public static void main(String[] args) {
        if (!UsernameValidator.isValid("alice")) throw new AssertionError("simple username rejected");
        if (UsernameValidator.isValid("")) throw new AssertionError("empty username accepted");
    }
}
