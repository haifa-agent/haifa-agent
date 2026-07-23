package sample;

public final class LegacySlugger {
    private LegacySlugger() {}

    public static String slug(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }
}
