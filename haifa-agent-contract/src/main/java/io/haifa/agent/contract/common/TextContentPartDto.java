package io.haifa.agent.contract.common;

public record TextContentPartDto(String text, String format) implements ContentPartDto {
    public TextContentPartDto {
        text = CorrelationId.requireText(text, "text", 65_536);
        format = CorrelationId.requireText(format, "format", 64);
    }

    @Override
    public String contentType() {
        return "text";
    }
}
