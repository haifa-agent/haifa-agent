package io.haifa.agent.application.coding.terminal.markdown.style;

/** Product-local Markdown colors. Values are independent of the terminal styling library. */
public record MarkdownTokens(
        Color heading, Color muted, Color text, Color codeForeground, Color codeBackground,
        Color strong, Color emphasis, Color listBullet, Color listNumber, Color rule,
        Color blockBackground, Color blockBorder) {
    public record Color(String light, String dark) {}

    public static MarkdownTokens forTheme(String name) {
        return switch (name) {
            case "light" -> colors("#0F6F6E", "#0F6F6E", "#64748B", "#64748B", "#0F172A", "#0F172A",
                    "#27632A", "#27632A", "#E8F0E8", "#E8F0E8", "#9A4814", "#9A4814", "#785600", "#785600",
                    "#356078", "#356078", "#4C5680", "#4C5680", "#E4EBF0", "#E4EBF0", "#526575", "#526575");
            case "high-contrast" -> colors("#00FFFF", "#00FFFF", "#A0A0A0", "#A0A0A0", "#FFFFFF", "#FFFFFF",
                    "#55FF55", "#55FF55", "#003300", "#003300", "#FFFF00", "#FFFF00", "#FFAA00", "#FFAA00",
                    "#55FFFF", "#55FFFF", "#FFAAFF", "#FFAAFF", "#242424", "#242424", "#AAAAAA", "#AAAAAA");
            case "nord" -> colors("#5E81AC", "#88C0D0", "#4C566A", "#D8DEE9", "#2E3440", "#ECEFF4",
                    "#395F32", "#A3BE8C", "#E5EDE2", "#343F35", "#845B17", "#EBCB8B", "#904A32", "#D08770",
                    "#436685", "#81A1C1", "#68507F", "#B48EAD", "#DCE2EA", "#292F3B", "#536D8A", "#81A1C1");
            default -> colors("#0F6F6E", "#79C7C5", "#71717A", "#A1A1AA", "#09090B", "#F4F4F5",
                    "#27632A", "#A6D18A", "#E8F0E8", "#1C2820", "#9A4814", "#E5AA70", "#785600", "#DDC078",
                    "#356078", "#84B8D0", "#615482", "#BEA6D5", "#E4EBF0", "#1C2128", "#526575", "#7F9EAF");
        };
    }

    private static MarkdownTokens colors(String... values) {
        Color[] c = new Color[11];
        for (int i = 0; i < c.length; i++) c[i] = new Color(values[i * 2], values[i * 2 + 1]);
        return new MarkdownTokens(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[1], c[9], c[10]);
    }
}
