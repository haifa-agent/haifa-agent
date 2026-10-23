package io.haifa.agent.application.coding.terminal.markdown.parse;

import java.util.List;

/** Immutable product Markdown subset. Offsets refer to the sanitized source, never terminal cells. */
public record MarkdownDocument(List<LocatedBlock> blocks) {
    public MarkdownDocument {
        blocks = List.copyOf(blocks);
    }

    public record LocatedBlock(Block block, int start, int end, boolean closed) {}

    public sealed interface Block permits Heading, Paragraph, ListBlock, Quote, CodeBlock, Rule, Blank, Table {}

    public record Heading(int level, List<Inline> content) implements Block {
        public Heading {
            content = List.copyOf(content);
        }
    }

    public record Paragraph(List<Inline> content) implements Block {
        public Paragraph {
            content = List.copyOf(content);
        }
    }

    public record ListBlock(boolean ordered, List<ListItem> items) implements Block {
        public ListBlock {
            items = List.copyOf(items);
        }
    }

    public record ListItem(String marker, int depth, List<Block> blocks) {
        public ListItem {
            blocks = List.copyOf(blocks);
        }
    }

    public record Quote(List<Block> blocks) implements Block {
        public Quote {
            blocks = List.copyOf(blocks);
        }
    }

    public record CodeBlock(String language, List<String> lines, boolean closed) implements Block {
        public CodeBlock {
            lines = List.copyOf(lines);
        }
    }

    public record Rule() implements Block {}

    public record Blank() implements Block {}

    public record Table(List<List<Inline>> header, List<List<List<Inline>>> rows) implements Block {
        public Table {
            header = header.stream().map(List::copyOf).toList();
            rows = rows.stream()
                    .map(row -> row.stream().map(List::copyOf).toList())
                    .toList();
        }
    }

    public sealed interface Inline permits Text, Strong, Emphasis, InlineCode, Link, Image {}

    public record Text(String value) implements Inline {}

    public record Strong(List<Inline> content) implements Inline {
        public Strong {
            content = List.copyOf(content);
        }
    }

    public record Emphasis(List<Inline> content) implements Inline {
        public Emphasis {
            content = List.copyOf(content);
        }
    }

    public record InlineCode(String value) implements Inline {}

    public record Link(List<Inline> label, String url) implements Inline {
        public Link {
            label = List.copyOf(label);
        }
    }

    public record Image(String alt, String url) implements Inline {}
}
