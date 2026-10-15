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

class TerminalDiffHighlighterTest {
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
    void classifiesToolCategoriesFromToolNameAccurately() {
        // READ category (existing project tools + web + aliases)
        assertThat(ToolCategory.fromToolName("file_read · README.md")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("file_stat")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("file_list · src/")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("file_diff · pom.xml")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("view_file")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("read_file")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("list_directory · src")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("glob_find")).isEqualTo(ToolCategory.READ);
        assertThat(ToolCategory.fromToolName("web_fetch · https://example.com")).isEqualTo(ToolCategory.READ);

        // EDIT category (existing project mutating tools + aliases)
        assertThat(ToolCategory.fromToolName("file_patch · pom.xml")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("file_write · test.txt")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("file_create · new.txt")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("file_delete · old.txt")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("file_move · a.txt -> b.txt")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("replace_file_content")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("write_to_file")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("patch_file")).isEqualTo(ToolCategory.EDIT);
        assertThat(ToolCategory.fromToolName("edit_file")).isEqualTo(ToolCategory.EDIT);

        // EXEC category
        assertThat(ToolCategory.fromToolName("execution_run · ./mvnw test")).isEqualTo(ToolCategory.EXEC);
        assertThat(ToolCategory.fromToolName("run_command")).isEqualTo(ToolCategory.EXEC);
        assertThat(ToolCategory.fromToolName("shell_exec")).isEqualTo(ToolCategory.EXEC);
        assertThat(ToolCategory.fromToolName("terminal_exec")).isEqualTo(ToolCategory.EXEC);

        // SEARCH category
        assertThat(ToolCategory.fromToolName("file_search · pattern")).isEqualTo(ToolCategory.SEARCH);
        assertThat(ToolCategory.fromToolName("grep_search · class")).isEqualTo(ToolCategory.SEARCH);
        assertThat(ToolCategory.fromToolName("search_code")).isEqualTo(ToolCategory.SEARCH);
        assertThat(ToolCategory.fromToolName("code_search")).isEqualTo(ToolCategory.SEARCH);
        assertThat(ToolCategory.fromToolName("web_search")).isEqualTo(ToolCategory.SEARCH);

        // TOOL fallback (avoids false-positive prefix/contains matching)
        assertThat(ToolCategory.fromToolName("workspace_attach · /dir")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName("exec_summary")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName("user_research")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName("search_indexer")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName("file_read_counter")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName("unknown_custom_tool")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName("")).isEqualTo(ToolCategory.TOOL);
        assertThat(ToolCategory.fromToolName(null)).isEqualTo(ToolCategory.TOOL);
    }

    @Test
    void identifiesDiffLinesAndHunkBlocksWithoutMisclassifyingContextLines() {
        assertThat(TerminalDiffHighlighter.isDiffLine("--- a/src/App.java")).isTrue();
        assertThat(TerminalDiffHighlighter.isDiffLine("+++ b/src/App.java")).isTrue();
        assertThat(TerminalDiffHighlighter.isDiffLine("@@ -10,5 +10,6 @@")).isTrue();
        assertThat(TerminalDiffHighlighter.isDiffLine("+int counter = 1;")).isTrue();
        assertThat(TerminalDiffHighlighter.isDiffLine("-int counter = 0;")).isTrue();
        assertThat(TerminalDiffHighlighter.isDiffLine("\\ No newline at end of file"))
                .isTrue();

        // Context lines starting with space MUST NOT be misclassified as diff added/removed
        assertThat(TerminalDiffHighlighter.isDiffLine(" int counter = -1;")).isFalse();
        assertThat(TerminalDiffHighlighter.isDiffLine(" -option")).isFalse();
        assertThat(TerminalDiffHighlighter.isDiffLine(" ++x;")).isFalse();
        assertThat(TerminalDiffHighlighter.isDiffLine("   int value = 0;")).isFalse();

        // Structural and regular lines
        assertThat(TerminalDiffHighlighter.isDiffLine("Target: src/App.java")).isFalse();
        assertThat(TerminalDiffHighlighter.isDiffLine("BUILD SUCCESS")).isFalse();
        assertThat(TerminalDiffHighlighter.isDiffLine("")).isFalse();
        assertThat(TerminalDiffHighlighter.isDiffLine(null)).isFalse();
    }

    @Test
    void contextLinesPreserveExactTextWithoutDiffColors() {
        String contextWithMinus = " int diff = -42;";
        String contextWithPlus = " int sum = +10;";
        String contextBullet = " - a note in comment";

        assertThat(TerminalDiffHighlighter.highlightLine(theme, contextWithMinus))
                .isEqualTo(contextWithMinus);
        assertThat(TerminalDiffHighlighter.highlightLine(theme, contextWithPlus))
                .isEqualTo(contextWithPlus);
        assertThat(TerminalDiffHighlighter.highlightLine(theme, contextBullet)).isEqualTo(contextBullet);
    }

    @Test
    void detectsWhetherTextBlockContainsDiffSafely() {
        String validDiff =
                """
                Target: src/Main.java
                Output:
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,3 +1,3 @@
                -oldLine
                +newLine
                 context
                Result: artifact:1
                """;
        assertThat(TerminalDiffHighlighter.containsDiff(validDiff)).isTrue();

        String nonDiffWithBullets =
                """
                Target: README.md
                Output:
                # Documentation
                ---
                - bullet point one
                - bullet point two
                + note about addition
                """;
        assertThat(TerminalDiffHighlighter.containsDiff(nonDiffWithBullets)).isFalse();

        String nonDiffBuildOutput =
                """
                Target: ./mvnw clean test
                Output:
                [INFO] Scanning for projects...
                [INFO] --- maven-compiler-plugin:3.14.0:compile (default-compile) ---
                [INFO] BUILD SUCCESS
                Result: 0
                """;
        assertThat(TerminalDiffHighlighter.containsDiff(nonDiffBuildOutput)).isFalse();
    }

    @Test
    void highlightsDiffLinesWithExactCharacterFidelity() {
        String added = "+    public void handleRequest() {";
        String removed = "-    public void processRequest() {";
        String hunk = "@@ -24,4 +24,4 @@";
        String headerA = "--- a/src/Service.java";
        String headerB = "+++ b/src/Service.java";

        String hlAdded = TerminalDiffHighlighter.highlightLine(theme, added);
        String hlRemoved = TerminalDiffHighlighter.highlightLine(theme, removed);
        String hlHunk = TerminalDiffHighlighter.highlightLine(theme, hunk);
        String hlHeaderA = TerminalDiffHighlighter.highlightLine(theme, headerA);
        String hlHeaderB = TerminalDiffHighlighter.highlightLine(theme, headerB);

        assertThat(hlAdded).contains("\u001B[").isNotEqualTo(hlRemoved);
        assertThat(hlRemoved).contains("\u001B[");
        assertThat(hlHunk).contains("\u001B[");
        assertThat(hlHeaderA).contains("\u001B[");
        assertThat(hlHeaderB).contains("\u001B[");

        assertThat(Strip.strip(hlAdded)).isEqualTo(added);
        assertThat(Strip.strip(hlRemoved)).isEqualTo(removed);
        assertThat(Strip.strip(hlHunk)).isEqualTo(hunk);
        assertThat(Strip.strip(hlHeaderA)).isEqualTo(headerA);
        assertThat(Strip.strip(hlHeaderB)).isEqualTo(headerB);
    }

    @Test
    void highlightsToolBodyDistinguishingHunksFromContextAndStructuralLines() {
        String body =
                """
                Target: src/App.java
                Output:
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1,4 +1,4 @@
                 int a = 1;
                -int b = -2;
                +int b = -3;
                 int c = -4;
                Result: artifact:1""";

        String rendered = TerminalDiffHighlighter.highlightToolBody(theme, body);

        assertThat(Strip.strip(rendered))
                .isEqualTo(
                        """
  Target: src/App.java
  Output:
  --- a/src/App.java
  +++ b/src/App.java
  @@ -1,4 +1,4 @@
   int a = 1;
  -int b = -2;
  +int b = -3;
   int c = -4;
  Result: artifact:1
"""
                                .stripTrailing());

        // The added line has ANSI escape sequence
        assertThat(rendered).contains("  \u001B[");
        // But the context lines (int a = 1, int c = -4) do not have diff color applied
        assertThat(rendered).contains("\n   int a = 1;\n");
        assertThat(rendered).contains("\n   int c = -4;\n");
    }

    @Test
    void degradesDiffHighlightingGracefullyUnderNoColor() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);

        String added = "+int a = 1;";
        String rendered = TerminalDiffHighlighter.highlightLine(theme, added);

        assertThat(rendered).isEqualTo(added);
    }
}
