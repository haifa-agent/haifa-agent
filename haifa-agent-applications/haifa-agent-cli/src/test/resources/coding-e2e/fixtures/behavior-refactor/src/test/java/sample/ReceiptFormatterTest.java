package sample;

public final class ReceiptFormatterTest {
    public static void main(String[] args) {
        if (!ReceiptFormatter.usd(105).equals("USD 1.05")) throw new AssertionError("USD changed");
        if (!ReceiptFormatter.cad(250).equals("CAD 2.50")) throw new AssertionError("CAD changed");
    }
}
