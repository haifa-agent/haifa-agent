package io.haifa.agent.application.coding.terminal.markdown.parse;

import static io.haifa.agent.application.coding.terminal.markdown.parse.MarkdownDocument.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownParserTest {
    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parsesListChildrenAndQuoteContainers() {
        var document = parser.parse(
                "- first\n\n  second paragraph\n  ```java\n  int x;\n  ```\n  - child\n    - leaf\n\n> - quoted\n");
        var list = (ListBlock) document.blocks().getFirst().block();
        var children = list.items().getFirst().blocks();
        assertThat(children)
                .anyMatch(Paragraph.class::isInstance)
                .anyMatch(CodeBlock.class::isInstance)
                .anyMatch(ListBlock.class::isInstance);
        var nested = (ListBlock) children.stream()
                .filter(ListBlock.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(nested.items().getFirst().depth()).isEqualTo(1);
        var leaf = (ListBlock) nested.items().getFirst().blocks().get(1);
        assertThat(leaf.items().getFirst().depth()).isEqualTo(2);
        var quote = (Quote) document.blocks().getLast().block();
        assertThat(quote.blocks().getFirst()).isInstanceOf(ListBlock.class);
        assertThat(((ListBlock) quote.blocks().getFirst()).items().getFirst().depth())
                .isZero();
    }

    @Test
    void preservesCodeBlanksAndDistinguishesUnclosedFence() {
        var partial = (CodeBlock)
                parser.parse("```text\na\n\nb\n").blocks().getFirst().block();
        assertThat(partial.closed()).isFalse();
        assertThat(partial.lines()).containsExactly("a", "", "b");
        var complete = (CodeBlock)
                parser.parse("```text\na\n\nb\n```").blocks().getFirst().block();
        assertThat(complete.closed()).isTrue();
        assertThat(complete.lines()).isEqualTo(partial.lines());
    }

    @Test
    void retainsHeadingLevelAndNestedInlineContent() {
        var heading = (Heading)
                parser.parse("### **bold and *soft***").blocks().getFirst().block();
        assertThat(heading.level()).isEqualTo(3);
        assertThat(heading.content().getFirst()).isInstanceOf(Strong.class);
        assertThat(((Strong) heading.content().getFirst()).content())
                .containsExactly(new Text("bold and "), new Emphasis(java.util.List.of(new Text("soft"))));
        var paragraph = (Paragraph)
                parser.parse("*soft with **bold** text*").blocks().getFirst().block();
        var emphasis = (Emphasis) paragraph.content().getFirst();
        assertThat(emphasis.content())
                .containsExactly(
                        new Text("soft with "), new Strong(java.util.List.of(new Text("bold"))), new Text(" text"));
    }

    @Test
    void parsesPipeTablesWithoutSplittingCodeOrEscapedPipes() {
        var table = (Table) parser.parse("| A | B |\n| --- | :---: |\n| `a|b` | c\\|d |\n")
                .blocks()
                .getFirst()
                .block();
        assertThat(table.header()).hasSize(2);
        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().getFirst().getFirst()).containsExactly(new InlineCode("a|b"));
        assertThat(table.rows().getFirst().get(1)).containsExactly(new Text("c|d"));
    }

    @Test
    void removesCompleteTerminalCommandsAndNormalizesInput() {
        assertThat(MarkdownInput.sanitize("a\u001B[31mb\u001B[0m\u001B]52;c;payload\u0007c\r\n\td\u009b2J"))
                .isEqualTo("abc\n    d");
        assertThat(MarkdownInput.sanitize("a\u001B]incomplete")).isEqualTo("a");
        assertThat(MarkdownInput.sanitize("a\u001B[3")).isEqualTo("a");
    }

    @Test
    void boundsRecursionOnDeepUntrustedContainers() {
        assertThat(parser.parse("> ".repeat(200) + "text").blocks()).isNotEmpty();
    }
}
