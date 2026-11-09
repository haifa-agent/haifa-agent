package io.haifa.coding.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Bounded CR/LF UTF-8 framing shared by presentation and preview transports. */
final class SseReader {
    @FunctionalInterface
    interface Frame {
        void accept(String data) throws IOException;
    }

    static void read(Reader reader, String expectedEvent, int maximumBytes, BooleanSupplier stopped, Frame consume)
            throws IOException {
        StringBuilder line = new StringBuilder(), data = new StringBuilder();
        String event = "";
        boolean afterCr = false;
        int cp;
        while (!stopped.getAsBoolean() && (cp = reader.read()) != -1) {
            if (afterCr && cp == '\n') {
                afterCr = false;
                continue;
            }
            afterCr = false;
            if (cp == '\r' || cp == '\n') {
                if (cp == '\r') afterCr = true;
                String value = line.toString();
                line.setLength(0);
                if (value.isEmpty()) {
                    if (!data.isEmpty()) {
                        if (!event.equals(expectedEvent)
                                || data.toString().getBytes(StandardCharsets.UTF_8).length > maximumBytes)
                            throw new IOException("Invalid SSE source or budget");
                        consume.accept(data.toString());
                        data.setLength(0);
                    }
                    event = "";
                } else if (value.startsWith("event:")) {
                    event = value.substring(6).strip();
                } else if (value.startsWith("data:")) {
                    String part = value.substring(5);
                    if (part.startsWith(" ")) part = part.substring(1);
                    if (!data.isEmpty()) data.append('\n');
                    data.append(part);
                    if (data.length() > maximumBytes) throw new IOException("SSE frame budget");
                }
            } else {
                line.append((char) cp);
                if (line.length() > maximumBytes) throw new IOException("SSE line budget");
            }
        }
    }
}
