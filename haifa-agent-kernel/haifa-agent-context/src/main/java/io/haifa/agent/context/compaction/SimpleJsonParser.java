package io.haifa.agent.context.compaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal, zero-dependency, pure Java JSON parser designed for structured output parsing.
 * Complies with strict architectural constraints banning third-party JSON libraries in pure domain layers.
 */
public final class SimpleJsonParser {

    private final String source;
    private int index;

    private SimpleJsonParser(String source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.index = 0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected JSON object but got: " + (result == null ? "null" : result.getClass().getSimpleName()));
    }

    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON string must not be blank");
        }
        SimpleJsonParser parser = new SimpleJsonParser(json.trim());
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.index < parser.source.length()) {
            throw new IllegalArgumentException("Unexpected character after JSON at position " + parser.index + ": " + parser.source.charAt(parser.index));
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (index >= source.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char c = source.charAt(index);
        return switch (c) {
            case '{' -> parseJsonObject();
            case '[' -> parseJsonArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield parseNumber();
                }
                throw new IllegalArgumentException("Unexpected character at position " + index + ": " + c);
            }
        };
    }

    private Map<String, Object> parseJsonObject() {
        match('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            match('}');
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            match(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == '}') {
                match('}');
                break;
            } else if (next == ',') {
                match(',');
            } else {
                throw new IllegalArgumentException("Expected ',' or '}' in object at position " + index);
            }
        }
        return map;
    }

    private List<Object> parseJsonArray() {
        match('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            match(']');
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ']') {
                match(']');
                break;
            } else if (next == ',') {
                match(',');
            } else {
                throw new IllegalArgumentException("Expected ',' or ']' in array at position " + index);
            }
        }
        return list;
    }

    private String parseString() {
        match('"');
        StringBuilder sb = new StringBuilder();
        while (index < source.length()) {
            char c = source.charAt(index++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (index >= source.length()) {
                    throw new IllegalArgumentException("Unterminated escape sequence");
                }
                char esc = source.charAt(index++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) {
                            throw new IllegalArgumentException("Incomplete unicode escape");
                        }
                        String hex = source.substring(index, index + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Illegal escape character: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Boolean parseBoolean() {
        if (source.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (source.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected boolean at position " + index);
    }

    private Object parseNull() {
        if (source.startsWith("null", index)) {
            index += 4;
            return null;
        }
        throw new IllegalArgumentException("Expected null at position " + index);
    }

    private Number parseNumber() {
        int start = index;
        if (source.charAt(index) == '-') {
            index++;
        }
        while (index < source.length() && Character.isDigit(source.charAt(index))) {
            index++;
        }
        boolean isFloating = false;
        if (index < source.length() && source.charAt(index) == '.') {
            isFloating = true;
            index++;
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }
        }
        if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
            isFloating = true;
            index++;
            if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                index++;
            }
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }
        }
        String numStr = source.substring(start, index);
        if (isFloating) {
            return Double.parseDouble(numStr);
        }
        try {
            return Long.parseLong(numStr);
        } catch (NumberFormatException e) {
            return Double.parseDouble(numStr);
        }
    }

    private void skipWhitespace() {
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                index++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (index >= source.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        return source.charAt(index);
    }

    private void match(char expected) {
        if (index >= source.length() || source.charAt(index) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at position " + index);
        }
        index++;
    }
}
