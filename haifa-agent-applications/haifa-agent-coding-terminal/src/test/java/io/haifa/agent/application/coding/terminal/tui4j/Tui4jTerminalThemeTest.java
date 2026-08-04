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

        assertThat(darkSuccess).contains("\u001B[").isNotEqualTo(darkPending);
        assertThat(darkPending).contains("\u001B[").isNotEqualTo(darkError);
        assertThat(darkError).contains("\u001B[").isNotEqualTo(darkQueued);
        assertThat(darkQueued).contains("\u001B[").isNotEqualTo(darkFocus);

        renderer.setHasDarkBackground(false);
        assertThat(theme.success("success")).contains("\u001B[").isNotEqualTo(darkSuccess);
    }

    @Test
    void keepsSemanticTextReadableWithoutColor() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);

        assertThat(theme.success("success")).isEqualTo(" success ");
        assertThat(theme.pending("pending")).isEqualTo(" pending ");
        assertThat(theme.error("error")).isEqualTo(" error ");
        assertThat(theme.queued("queued")).isEqualTo(" queued ");
        assertThat(theme.focus("focus")).isEqualTo("focus");
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
                    .isEqualTo(" 错误 🚀 ");
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
}
