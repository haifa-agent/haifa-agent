package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.lipgloss.Renderer;
import com.williamcallahan.tui4j.compat.lipgloss.color.ColorProfile;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.compat.x.ansi.Strip;
import com.williamcallahan.tui4j.term.TerminalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TerminalSyntaxHighlighterTest {
    private final Renderer renderer = Renderer.defaultRenderer();
    private final Tui4jTerminalTheme theme = new Tui4jTerminalTheme();

    @BeforeEach
    void setUp() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);
    }

    @AfterEach
    void tearDown() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @Test
    void normalizesLanguageAliasesCorrectly() {
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("java")).isEqualTo("java");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("py")).isEqualTo("python");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("python3")).isEqualTo("python");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("js")).isEqualTo("javascript");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("typescript")).isEqualTo("typescript");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("ts")).isEqualTo("typescript");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("bash")).isEqualTo("shell");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("sh")).isEqualTo("shell");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("zsh")).isEqualTo("shell");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("json")).isEqualTo("json");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("jsonc")).isEqualTo("json");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("yml")).isEqualTo("yaml");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("yaml")).isEqualTo("yaml");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("sql")).isEqualTo("sql");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("go")).isEqualTo("go");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("golang")).isEqualTo("go");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("md")).isEqualTo("markdown");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("rust")).isEqualTo("rust");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("cpp")).isEqualTo("cpp");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("unknown_lang")).isEqualTo("unknown_lang");
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage("")).isEmpty();
        assertThat(TerminalSyntaxHighlighter.normalizeLanguage(null)).isEmpty();
    }

    @Test
    void highlightsJavaCodeWithCompleteCharacterFidelity() {
        String javaLine = "    public static final String GREETING = \"Hello, World!\"; // greeting";
        String highlighted = TerminalSyntaxHighlighter.highlight(theme, "java", javaLine);

        assertThat(highlighted).contains("\u001B[");
        assertThat(Strip.strip(highlighted)).isEqualTo(javaLine);
    }

    @Test
    void highlightsPythonCodeAndComments() {
        String pythonLine = "def calculate_sum(a: int, b: int = 42) -> int: # computes total";
        String highlighted = TerminalSyntaxHighlighter.highlight(theme, "python", pythonLine);

        assertThat(highlighted).contains("\u001B[");
        assertThat(Strip.strip(highlighted)).isEqualTo(pythonLine);
    }

    @Test
    void highlightsJsonPropertiesDistinctFromValues() {
        String jsonLine = "  \"endpointUrl\": \"https://api.haifa.io/v1\",";
        String highlighted = TerminalSyntaxHighlighter.highlight(theme, "json", jsonLine);

        assertThat(highlighted).contains("\u001B[");
        assertThat(Strip.strip(highlighted)).isEqualTo(jsonLine);
    }

    @Test
    void highlightsShellCommandsAndVariables() {
        String shellLine = "export PATH=\"$HOME/bin:$PATH\" # set user path";
        String highlighted = TerminalSyntaxHighlighter.highlight(theme, "bash", shellLine);

        assertThat(highlighted).contains("\u001B[");
        assertThat(Strip.strip(highlighted)).isEqualTo(shellLine);
    }

    @Test
    void highlightsSqlKeywordsCaseInsensitively() {
        String sqlLine = "SELECT id, name FROM users WHERE active = true ORDER BY created_at DESC;";
        String highlighted = TerminalSyntaxHighlighter.highlight(theme, "sql", sqlLine);

        assertThat(highlighted).contains("\u001B[");
        assertThat(Strip.strip(highlighted)).isEqualTo(sqlLine);
    }

    @Test
    void degradesGracefullyOnUnknownLanguageWithoutDroppingText() {
        String customText = "FOO :: bar -> baz [123]";
        String highlighted = TerminalSyntaxHighlighter.highlight(theme, "custom_unsupported", customText);

        assertThat(Strip.strip(highlighted)).isEqualTo(customText);
    }

    @Test
    void handlesEmptyAndBlankLinesSafely() {
        assertThat(TerminalSyntaxHighlighter.highlight(theme, "java", "")).isEmpty();
        assertThat(TerminalSyntaxHighlighter.highlight(theme, "java", null)).isEmpty();
        String spaces = "    ";
        assertThat(Strip.strip(TerminalSyntaxHighlighter.highlight(theme, "java", spaces)))
                .isEqualTo(spaces);
    }

    @Test
    void preservesCodeUnderNoColorMode() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);

        String code = "const count: number = 100;";
        String rendered = TerminalSyntaxHighlighter.highlight(theme, "ts", code);

        assertThat(rendered).isEqualTo(code);
    }
}
