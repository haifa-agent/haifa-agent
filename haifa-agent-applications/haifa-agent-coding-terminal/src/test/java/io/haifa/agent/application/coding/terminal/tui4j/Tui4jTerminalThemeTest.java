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
    }
}
