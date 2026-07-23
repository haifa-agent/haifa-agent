package sample;

public final class UsernameValidator {
    private UsernameValidator() {}

    public static boolean isValid(String username) {
        return username != null && !username.isBlank() && username.length() <= 20;
    }
}
