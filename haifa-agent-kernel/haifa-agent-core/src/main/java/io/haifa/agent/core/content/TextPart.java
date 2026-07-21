package io.haifa.agent.core.content;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Inline textual content in a declared format. */
public record TextPart(String text, String format) implements ContentPart {
    public TextPart {
        text = requireText(text, "text");
        format = requireText(format, "format");
    }

    @Override
    public String contentType() {
        return "text";
    }
}
