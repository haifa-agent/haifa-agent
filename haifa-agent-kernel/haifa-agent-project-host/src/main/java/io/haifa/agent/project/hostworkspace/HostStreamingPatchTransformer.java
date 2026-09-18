package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.project.patch.FilePatch;
import io.haifa.agent.project.patch.PatchHunk;
import io.haifa.agent.project.patch.PatchLine;
import io.haifa.agent.project.patch.PatchLineType;
import io.haifa.agent.project.patch.PatchTransformException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class HostStreamingPatchTransformer {
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final byte[] LF = {'\n'};

    long transform(FilePatch patch, InputStream source, OutputStream destination, long maxOutputBytes)
            throws IOException {
        try (var reader = new Utf8LineReader(source);
                var writer = new PendingLineWriter(destination, maxOutputBytes)) {
            if (reader.hasBom()) writer.writePrefix(UTF8_BOM);
            SourceCursor cursor = new SourceCursor(reader);
            for (int index = 0; index < patch.hunks().size(); index++) {
                PatchHunk hunk = patch.hunks().get(index);
                if (hunk.locateByContent()) {
                    applyContextual(index, hunk, cursor, writer);
                } else {
                    applyPositional(index, hunk, cursor, writer);
                }
            }
            LineRecord remaining;
            while ((remaining = cursor.next()) != null) writer.accept(remaining, cursor.preferredNewline());
            writer.finish(patch.newEndsWithNewline(), cursor.preferredNewline());
            return writer.bytesWritten();
        }
    }

    private static void applyPositional(int hunkIndex, PatchHunk hunk, SourceCursor cursor, PendingLineWriter writer)
            throws IOException {
        long target = hunk.oldStart() == 0 ? 0 : hunk.oldStart() - 1L;
        while (cursor.linesRead() < target) {
            LineRecord line = cursor.next();
            if (line == null) throw conflict(hunkIndex, "hunk location is outside the source");
            writer.accept(line, cursor.preferredNewline());
        }
        List<LineRecord> matched = readExpected(hunkIndex, oldLines(hunk), cursor);
        writeReplacement(hunkIndex, hunk, matched, cursor, writer);
    }

    private static void applyContextual(int hunkIndex, PatchHunk hunk, SourceCursor cursor, PendingLineWriter writer)
            throws IOException {
        if (hunk.changeContext() != null) {
            LineRecord line;
            boolean found = false;
            while ((line = cursor.next()) != null) {
                writer.accept(line, cursor.preferredNewline());
                if (matches(line, hunk.changeContext())) {
                    found = true;
                    break;
                }
            }
            if (!found) throw conflict(hunkIndex, "failed to find change context");
        }

        List<String> expected = oldLines(hunk);
        if (expected.isEmpty()) {
            LineRecord line;
            while ((line = cursor.next()) != null) writer.accept(line, cursor.preferredNewline());
            writeReplacement(hunkIndex, hunk, List.of(), cursor, writer);
            return;
        }

        ArrayDeque<LineRecord> window = new ArrayDeque<>(expected.size());
        while (window.size() < expected.size()) {
            LineRecord line = cursor.next();
            if (line == null) throw conflict(hunkIndex, "failed to find expected lines");
            window.addLast(line);
        }
        while (!matches(window, expected)) {
            writer.accept(window.removeFirst(), cursor.preferredNewline());
            LineRecord line = cursor.next();
            if (line == null) throw conflict(hunkIndex, "failed to find expected lines");
            window.addLast(line);
        }
        if (hunk.endOfFile() && cursor.peek() != null) {
            throw conflict(hunkIndex, "expected lines are not at end of file");
        }
        writeReplacement(hunkIndex, hunk, List.copyOf(window), cursor, writer);
    }

    private static List<LineRecord> readExpected(int hunkIndex, List<String> expected, SourceCursor cursor)
            throws IOException {
        List<LineRecord> matched = new ArrayList<>(expected.size());
        for (String value : expected) {
            LineRecord line = cursor.next();
            if (line == null || !matches(line, value)) {
                throw conflict(hunkIndex, "hunk context does not match exactly");
            }
            matched.add(line);
        }
        return matched;
    }

    private static void writeReplacement(
            int hunkIndex, PatchHunk hunk, List<LineRecord> matched, SourceCursor cursor, PendingLineWriter writer)
            throws IOException {
        int sourceIndex = 0;
        boolean replacesBomLine = matched.stream().anyMatch(LineRecord::firstSourceLine);
        for (PatchLine line : hunk.lines()) {
            if (line.type() == PatchLineType.ADD) {
                String text = replacesBomLine && line.text().startsWith("\uFEFF")
                        ? line.text().substring(1)
                        : line.text();
                writer.accept(LineRecord.added(text, cursor.preferredNewline()), cursor.preferredNewline());
                continue;
            }
            if (sourceIndex >= matched.size()) throw conflict(hunkIndex, "hunk source line count is inconsistent");
            LineRecord source = matched.get(sourceIndex++);
            if (line.type() == PatchLineType.CONTEXT) writer.accept(source, cursor.preferredNewline());
        }
        if (sourceIndex != matched.size()) throw conflict(hunkIndex, "hunk source line count is inconsistent");
    }

    private static List<String> oldLines(PatchHunk hunk) {
        return hunk.lines().stream()
                .filter(line -> line.type() != PatchLineType.ADD)
                .map(PatchLine::text)
                .toList();
    }

    private static boolean matches(ArrayDeque<LineRecord> window, List<String> expected) {
        int index = 0;
        for (LineRecord line : window) {
            if (!matches(line, expected.get(index++))) return false;
        }
        return true;
    }

    private static boolean matches(LineRecord line, String expected) {
        String normalized = line.firstSourceLine() && expected.startsWith("\uFEFF") ? expected.substring(1) : expected;
        return line.text().equals(normalized);
    }

    private static PatchTransformException conflict(int hunkIndex, String message) {
        return new PatchTransformException(hunkIndex, message);
    }

    private record LineRecord(String text, byte[] content, byte[] terminator, boolean firstSourceLine) {
        private static LineRecord added(String text, byte[] newline) {
            return new LineRecord(text, text.getBytes(StandardCharsets.UTF_8), newline, false);
        }
    }

    private static final class SourceCursor {
        private final Utf8LineReader reader;
        private LineRecord lookahead;
        private long linesRead;

        private SourceCursor(Utf8LineReader reader) {
            this.reader = reader;
        }

        private LineRecord next() throws IOException {
            LineRecord value = lookahead == null ? reader.readLine() : lookahead;
            lookahead = null;
            if (value != null) linesRead++;
            return value;
        }

        private LineRecord peek() throws IOException {
            if (lookahead == null) lookahead = reader.readLine();
            return lookahead;
        }

        private long linesRead() {
            return linesRead;
        }

        private byte[] preferredNewline() {
            return reader.preferredNewline();
        }
    }

    private static final class Utf8LineReader implements AutoCloseable {
        private final PushbackInputStream input;
        private final boolean bom;
        private byte[] preferredNewline = LF;
        private boolean foundNewline;
        private boolean firstLine = true;

        private Utf8LineReader(InputStream source) throws IOException {
            input = new PushbackInputStream(new BufferedInputStream(source, 64 * 1024), 3);
            byte[] prefix = input.readNBytes(3);
            bom = prefix.length == 3
                    && prefix[0] == UTF8_BOM[0]
                    && prefix[1] == UTF8_BOM[1]
                    && prefix[2] == UTF8_BOM[2];
            if (!bom && prefix.length > 0) input.unread(prefix);
        }

        private boolean hasBom() {
            return bom;
        }

        private byte[] preferredNewline() {
            return preferredNewline;
        }

        private LineRecord readLine() throws IOException {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] terminator = new byte[0];
            int value;
            while ((value = input.read()) >= 0) {
                if (value == '\n') {
                    terminator = LF;
                    break;
                }
                if (value == '\r') {
                    int next = input.read();
                    if (next == '\n') terminator = new byte[] {'\r', '\n'};
                    else {
                        terminator = new byte[] {'\r'};
                        if (next >= 0) input.unread(next);
                    }
                    break;
                }
                content.write(value);
                if (content.size() > MAX_LINE_BYTES) throw new IOException("source line exceeds patch limit");
            }
            if (value < 0 && content.size() == 0) return null;
            if (!foundNewline && terminator.length > 0) {
                preferredNewline = terminator;
                foundNewline = true;
            }
            byte[] bytes = content.toByteArray();
            boolean firstSourceLine = firstLine;
            firstLine = false;
            return new LineRecord(decode(bytes), bytes, terminator, firstSourceLine);
        }

        private static String decode(byte[] bytes) throws IOException {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("source file is not valid UTF-8", exception);
            }
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static final class PendingLineWriter implements AutoCloseable {
        private final BufferedOutputStream output;
        private final long maxBytes;
        private LineRecord pending;
        private long bytesWritten;

        private PendingLineWriter(OutputStream output, long maxBytes) {
            this.output = new BufferedOutputStream(output, 64 * 1024);
            this.maxBytes = maxBytes;
        }

        private void writePrefix(byte[] bytes) throws IOException {
            write(bytes);
        }

        private void accept(LineRecord line, byte[] preferredNewline) throws IOException {
            if (pending != null) writeLine(pending, terminator(pending, preferredNewline));
            pending = line;
        }

        private void finish(boolean endsWithNewline, byte[] preferredNewline) throws IOException {
            if (pending != null) {
                writeLine(pending, endsWithNewline ? terminator(pending, preferredNewline) : new byte[0]);
                pending = null;
            }
            output.flush();
        }

        private static byte[] terminator(LineRecord line, byte[] preferredNewline) {
            return line.terminator().length == 0 ? preferredNewline : line.terminator();
        }

        private void writeLine(LineRecord line, byte[] terminator) throws IOException {
            write(line.content());
            write(terminator);
        }

        private void write(byte[] bytes) throws IOException {
            if (bytesWritten > maxBytes - bytes.length) throw new IOException("patched file exceeds output limit");
            output.write(bytes);
            bytesWritten += bytes.length;
        }

        private long bytesWritten() {
            return bytesWritten;
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }
}
