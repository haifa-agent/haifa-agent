package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.lipgloss.Renderer;
import com.williamcallahan.tui4j.compat.lipgloss.color.ColorProfile;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.term.TerminalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Tui4jTerminalThemeTest {
    private final Renderer renderer = Renderer.defaultRenderer();
    private final Tui4jTerminalTheme theme = new Tui4jTerminalTheme();

    @AfterEach
    void restoreNoColorRenderer() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @Test
    void emitsDistinctAdaptiveTrueColorStylesForSemanticStates() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);

        String darkSuccess = theme.success("success");
        String darkPending = theme.pending("pending");
        String darkError = theme.error("error");
        String darkQueued = theme.queued("queued");
        String darkFocus = theme.focus("focus");
        String darkSelected = theme.selected("selected");
        String darkUnselected = theme.unselected("unselected");

        assertThat(darkSuccess).contains("\u001B[").isNotEqualTo(darkPending);
        assertThat(darkPending).contains("\u001B[").isNotEqualTo(darkError);
        assertThat(darkError).contains("\u001B[").isNotEqualTo(darkQueued);
        assertThat(darkQueued).contains("\u001B[").isNotEqualTo(darkFocus);
        assertThat(darkSelected).contains("\u001B[").isNotEqualTo(darkFocus).isNotEqualTo(darkUnselected);

        renderer.setHasDarkBackground(false);
        assertThat(theme.success("success")).contains("\u001B[").isNotEqualTo(darkSuccess);
    }

    @Test
    void keepsSemanticTextReadableWithoutColor() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);

        assertThat(theme.success("success")).isEqualTo("success");
        assertThat(theme.pending("pending")).isEqualTo("pending");
        assertThat(theme.error("error")).isEqualTo("error");
        assertThat(theme.queued("queued")).isEqualTo(" queued ");
        assertThat(theme.focus("focus")).isEqualTo("focus");
        assertThat(theme.selected("selected")).isEqualTo("selected");
        assertThat(theme.unselected("unselected")).isEqualTo("unselected");
        assertThat(theme.heading("heading")).isEqualTo("heading");
        assertThat(theme.strong("strong")).isEqualTo("strong");
        assertThat(theme.emphasis("emphasis")).isEqualTo("emphasis");
        assertThat(theme.inlineCode("code")).isEqualTo("code");
        assertThat(theme.codeBlock("block")).isEqualTo("block");
        assertThat(theme.quote("quote")).isEqualTo("quote");
        assertThat(theme.link("link")).isEqualTo("link");
    }

    @Test
    void preservesTextAndClosesStylesForAnsi16Ansi256AndTrueColorProfiles() {
        for (ColorProfile profile :
                new ColorProfile[] {ColorProfile.ANSI, ColorProfile.ANSI256, ColorProfile.TrueColor}) {
            renderer.setColorProfile(profile);
            renderer.setHasDarkBackground(true);

            String rendered = theme.error("错误 🚀");

            assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(rendered))
                    .isEqualTo("错误 🚀");
            assertThat(rendered).contains("\u001B[").endsWith("\u001B[0m");

            String markdownStyles = theme.heading("heading")
                    + theme.strong("strong")
                    + theme.emphasis("emphasis")
                    + theme.inlineCode("code")
                    + theme.codeBlock("block")
                    + theme.quote("quote")
                    + theme.link("link");
            assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(markdownStyles))
                    .isEqualTo("headingstrongemphasiscodeblockquotelink");
            assertThat(markdownStyles).contains("\u001B[");
        }
    }

    @Test
    void rendersUserAndErrorCardLinesWithAppropriateBordersAndNoColorGrace() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);

        String userHeaderNormal = theme.userCardLine("❯ You          ", false, true);
        String userBodyNormal = theme.userCardLine("Fix retry loop ", false, false);
        String userHeaderNarrow = theme.userCardLine("❯ You          ", true, true);
        String errorHeaderNormal = theme.errorCardLine("✖ Error · Failed", false, true);
        String errorHeaderNarrow = theme.errorCardLine("✖ Error · Failed", true, true);

        assertThat(userHeaderNormal).isEqualTo("│ ❯ You           ");
        assertThat(userBodyNormal).isEqualTo("│ Fix retry loop  ");
        assertThat(userHeaderNarrow).isEqualTo(" ❯ You           ");
        assertThat(errorHeaderNormal).isEqualTo("│ ✖ Error · Failed");
        assertThat(errorHeaderNarrow).isEqualTo(" ✖ Error · Failed");

        assertThat(userHeaderNormal).doesNotContain("\u001B");
        assertThat(errorHeaderNormal).doesNotContain("\u001B");
    }

    @Test
    void stylesUserAndErrorCardLinesWithAdaptiveTrueColorAndClosesSequences() {
        for (boolean dark : new boolean[] {true, false}) {
            renderer.setColorProfile(ColorProfile.TrueColor);
            renderer.setHasDarkBackground(dark);

            String userHeader = theme.userCardLine("❯ You", false, true);
            String userBody = theme.userCardLine("Fix loop", false, false);
            String errorHeader = theme.errorCardLine("✖ Error", false, true);
            String errorBody = theme.errorCardLine("Broken", false, false);

            assertThat(userHeader).contains("\u001B[").isNotEqualTo(userBody);
            assertThat(errorHeader).contains("\u001B[").isNotEqualTo(errorBody);
            assertThat(userHeader).isNotEqualTo(errorHeader);

            assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(userHeader))
                    .isEqualTo("│ ❯ You ");
            assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(errorHeader))
                    .isEqualTo("│ ✖ Error");

            String subtle = theme.borderSubtle("│");
            assertThat(subtle).contains("\u001B[");
            assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(subtle))
                    .isEqualTo("│");
        }
    }

    @Test
    void statusStylesNeverContainBackgroundSequencesAcrossThemesAndProfiles() {
        for (String themeName : Tui4jTerminalTheme.availableThemeNames()) {
            theme.switchTheme(themeName);
            for (ColorProfile profile : new ColorProfile[] {
                ColorProfile.Ascii, ColorProfile.ANSI, ColorProfile.ANSI256, ColorProfile.TrueColor
            }) {
                for (boolean dark : new boolean[] {true, false}) {
                    renderer.setColorProfile(profile);
                    renderer.setHasDarkBackground(dark);

                    String succ = theme.success("Operation succeeded");
                    String pend = theme.pending("Operation pending");
                    String err = theme.error("Operation failed");
                    String warn = theme.warning("Warning message");
                    String errHeader = theme.errorCardLine("✖ Error · Failed", false, true);
                    String errBody = theme.errorCardLine("Reason: detail", false, false);

                    // SGR background codes begin with [4 or [10
                    assertThat(succ).doesNotContain("\u001B[4").doesNotContain("\u001B[10");
                    assertThat(pend).doesNotContain("\u001B[4").doesNotContain("\u001B[10");
                    assertThat(err).doesNotContain("\u001B[4").doesNotContain("\u001B[10");
                    assertThat(warn).doesNotContain("\u001B[4").doesNotContain("\u001B[10");
                    assertThat(errHeader).doesNotContain("\u001B[4").doesNotContain("\u001B[10");
                    assertThat(errBody).doesNotContain("\u001B[4").doesNotContain("\u001B[10");

                    assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(succ))
                            .isEqualTo("Operation succeeded");
                    assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(pend))
                            .isEqualTo("Operation pending");
                    assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(err))
                            .isEqualTo("Operation failed");
                    assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(warn))
                            .isEqualTo("Warning message");
                    assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(errHeader))
                            .isEqualTo("│ ✖ Error · Failed");
                    assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(errBody))
                            .isEqualTo("│ Reason: detail");
                }
            }
        }
    }

    @Test
    void supportsSwitchingBetweenBuiltinThemes() {
        assertThat(Tui4jTerminalTheme.availableThemeNames()).containsExactly("dark", "light", "high-contrast", "nord");

        assertThat(theme.currentThemeName()).isEqualTo("dark");

        boolean switchedToLight = theme.switchTheme("light");
        assertThat(switchedToLight).isTrue();
        assertThat(theme.currentThemeName()).isEqualTo("light");

        boolean switchedToHc = theme.switchTheme("high-contrast");
        assertThat(switchedToHc).isTrue();
        assertThat(theme.currentThemeName()).isEqualTo("high-contrast");

        boolean switchedToNord = theme.switchTheme("nord");
        assertThat(switchedToNord).isTrue();
        assertThat(theme.currentThemeName()).isEqualTo("nord");

        boolean switchedInvalid = theme.switchTheme("nonexistent");
        assertThat(switchedInvalid).isFalse();
        assertThat(theme.currentThemeName()).isEqualTo("nord");
    }

    @Test
    void stylesSyntaxTokensWithConsistentBackgroundAcrossThemes() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);

        String keyword = theme.syntaxKeyword("public");
        String string = theme.syntaxString("\"hello\"");
        String comment = theme.syntaxComment("// comment");
        String number = theme.syntaxNumber("42");
        String type = theme.syntaxType("String");
        String literal = theme.syntaxLiteral("true");
        String punctuation = theme.syntaxPunctuation(";");

        assertThat(keyword).contains("\u001B[").isNotEqualTo(string);
        assertThat(string).contains("\u001B[").isNotEqualTo(comment);
        assertThat(number).contains("\u001B[").isNotEqualTo(type);
        assertThat(literal).contains("\u001B[").isNotEqualTo(punctuation);

        assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(keyword)).isEqualTo("public");
        assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(string)).isEqualTo("\"hello\"");
        assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(comment)).isEqualTo("// comment");
    }

    @Test
    void nordThemeAdaptsColorsForLightAndDarkHostTerminals() {
        theme.switchTheme("nord");
        renderer.setColorProfile(ColorProfile.TrueColor);

        renderer.setHasDarkBackground(true);
        String darkText = theme.syntaxPlain("text");
        String darkKeyword = theme.syntaxKeyword("class");

        renderer.setHasDarkBackground(false);
        String lightText = theme.syntaxPlain("text");
        String lightKeyword = theme.syntaxKeyword("class");

        assertThat(darkText).isNotEqualTo(lightText);
        assertThat(darkKeyword).isNotEqualTo(lightKeyword);
        assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(lightText))
                .isEqualTo("text");
    }

    @Test
    void markdownStylesHaveDedicatedColorsAndUniformCodeBackgrounds() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        for (String name : Tui4jTerminalTheme.availableThemeNames()) {
            theme.switchTheme(name);
            for (boolean dark : new boolean[] {true, false}) {
                renderer.setHasDarkBackground(dark);
                assertThat(theme.heading(1, "Title")).isNotEqualTo(theme.heading(3, "Title"));
                assertThat(theme.strong("x")).contains("\u001B[").isNotEqualTo(theme.accent("x"));
                assertThat(theme.emphasis("x")).contains("\u001B[");
                assertThat(theme.inlineCode("x")).isNotEqualTo(theme.queued("x"));
                String background = theme.markdownTokens().blockBackground().dark();
                if (!dark) background = theme.markdownTokens().blockBackground().light();
                int rgb = Integer.parseInt(background.substring(1), 16);
                String sgr = "48;2;" + (rgb >> 16) + ";" + ((rgb >> 8) & 255) + ";" + (rgb & 255);
                assertThat(java.util.List.of(
                                theme.syntaxKeyword("if"),
                                theme.syntaxPlain("name "),
                                theme.syntaxString("s"),
                                theme.syntaxComment("c"),
                                theme.syntaxNumber("1"),
                                theme.syntaxType("T"),
                                theme.syntaxLiteral("true"),
                                theme.syntaxPunctuation(";"),
                                theme.codeBlock("plain")))
                        .allMatch(value -> value.contains(sgr));
                assertThat(theme.user("message")).doesNotContain(sgr);
            }
        }
    }
}
