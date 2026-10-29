package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import java.time.Duration;

/**
 * One batch of text keys at the composer: the run that starts at its first character, the break that shows the run is
 * paste content, and everything the host keeps streaming behind that break.
 *
 * <p>A host that sends no bracketed-paste markers delivers a paste as ordinary keys, so the key stream alone cannot
 * tell a paste from typing. This batch is the single place that decides: it records the batch's own insertion range so
 * the text the user had before and after the paste stays editable, it replaces painted text with a receiving block and
 * holds the rest of the stream back instead of painting one key at a time, and it settles into the same paste summary
 * the bracketed path produces.
 *
 * <p>Only content decides that: a break the host delivers, or a break the following text continues. Key timing cannot
 * separate a fast typist from a host streaming a clipboard, so it never starts a block and never swallows an Enter.
 *
 * <p>The batch never owns text the composer already shows: {@link #start()} and {@link #end()} bound the live range
 * inside the editor buffer, and {@link #held()} carries the text still waiting at {@link #end()}.
 */
final class StreamedInputBatch {
    /**
     * Text keys further apart than this belong to different batches. A host streams one paste well inside the window,
     * so an idle gap is also what keeps an older draft out of the next paste's range.
     */
    static final Duration IDLE_INTERVAL = Duration.ofMillis(100);

    private static final String TAB_TEXT = "    ";
    private static final long IDLE_INTERVAL_NANOS = IDLE_INTERVAL.toNanos();

    private final int start;
    private final StringBuilder held = new StringBuilder();
    private int end;
    private long lastKeyNanos;
    private boolean previousKeyKnown;
    private boolean endsWithCr;
    private boolean paste;
    private String blockLabel;
    private int newlines;
    private int trailingNewlines;

    private StreamedInputBatch(int start, int end, long nowNanos) {
        this.start = Math.max(0, Math.min(start, end));
        this.end = Math.max(this.start, end);
        this.lastKeyNanos = nowNanos;
    }

    /** Starts a batch whose first text key was just written to the composer at {@code end}. */
    static StreamedInputBatch begin(int start, int end, long nowNanos) {
        return new StreamedInputBatch(start, end, nowNanos);
    }

    /**
     * The text one key contributes to the composer, or {@code null} when the key is not text. tui4j reports Space,
     * Tab and the breaks as their own key types instead of runes, so every text key is decoded here and nowhere else.
     */
    static String textOf(KeyPressMessage key) {
        KeyType type = key.type();
        if (type == KeyType.KeyRunes) {
            char[] runes = key.runes();
            return runes == null ? "" : new String(runes);
        }
        if (type == KeyType.KeySpace) {
            return " ";
        }
        if (type == KeyType.keyHT) {
            return TAB_TEXT;
        }
        if (type == KeyType.keyCR || type == KeyType.keyLF) {
            return "\n";
        }
        return null;
    }

    int start() {
        return start;
    }

    /** The offset just behind the text this batch already wrote to the composer. */
    int end() {
        return end;
    }

    /** The text the batch keeps back so a streamed paste never paints one key at a time. */
    String held() {
        return held.toString();
    }

    /** The receiving block the composer shows for this batch, or {@code null} while the batch paints its live text. */
    String blockLabel() {
        return blockLabel;
    }

    /**
     * How many lines the block reports for the content received so far. It ignores the trailing break that the host may
     * still be about to continue, so the count only grows when a line really starts.
     */
    int displayLines() {
        return Math.max(1, newlines - trailingNewlines + 1);
    }

    /** Whether the batch is paste content, so a following break belongs to it instead of submitting. */
    boolean isPaste() {
        return paste;
    }

    /** Whether the last key was a CR, so the LF that follows it is the other half of the same CRLF pair. */
    boolean endsWithCr() {
        return endsWithCr;
    }

    /** Whether a text key arriving now still belongs to this batch, or whether the batch went idle. */
    boolean continuesAt(long nowNanos) {
        return previousKeyKnown && nowNanos - lastKeyNanos <= IDLE_INTERVAL_NANOS;
    }

    /** Records live text the composer inserted, so the batch's own range stays exact. */
    void extend(int end, long nowNanos) {
        this.end = Math.max(this.end, end);
        this.endsWithCr = false;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }

    /** Turns the deferred break into paste content; the rest of the stream is held until the batch settles. */
    void confirmAsPaste(long nowNanos) {
        this.paste = true;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }

    /**
     * Moves the text the composer already painted into the held range, so the block can report how much arrived. The
     * range still starts where the batch started, so the draft around the block keeps its own text and the settle still
     * replaces only the batch.
     */
    void receivePaintedText(String liveText, long nowNanos) {
        appendHeld(liveText);
        this.paste = true;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }

    /** Shows {@code label} inside the batch's own range; the caller formats it from {@link #displayLines()}. */
    void showBlock(String label) {
        this.blockLabel = label;
        this.end = start + label.length();
    }

    /** Holds one key's text back until the batch settles. */
    void hold(String text, boolean breakFromCr, long nowNanos) {
        appendHeld(text);
        this.endsWithCr = breakFromCr;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }

    /** Appends one received fragment and keeps the line counters of the block up to date. */
    private void appendHeld(String text) {
        if (text.isEmpty()) {
            return;
        }
        held.append(text);
        int chunkNewlines = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                chunkNewlines++;
            }
        }
        newlines += chunkNewlines;
        int chunkTrailing = 0;
        while (chunkTrailing < text.length() && text.charAt(text.length() - 1 - chunkTrailing) == '\n') {
            chunkTrailing++;
        }
        trailingNewlines = chunkTrailing == text.length() ? trailingNewlines + chunkTrailing : chunkTrailing;
    }

    /** Consumes the LF half of a CRLF pair whose CR half already held the one break. */
    void consumeCrLfHalf(long nowNanos) {
        this.endsWithCr = false;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }
}
