package sample;

public final class ReceiptFormatter {
    private ReceiptFormatter() {}

    public static String usd(int cents) {
        int major = cents / 100;
        int minor = Math.abs(cents % 100);
        return "USD " + major + "." + (minor < 10 ? "0" : "") + minor;
    }

    public static String cad(int cents) {
        int major = cents / 100;
        int minor = Math.abs(cents % 100);
        return "CAD " + major + "." + (minor < 10 ? "0" : "") + minor;
    }
}
