package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.input.key.KeyType;
import java.time.Duration;

/**
 * One batch of text keys at the composer: the run that starts at its first character, a break that a following key
 * proves to be paste content, and everything the host keeps streaming behind that break.
 *
 * <p>A host that sends no bracketed-paste markers delivers a paste as ordinary keys, so the key stream alone cannot
 * tell a paste from typing. This batch is the single place that decides: it records the batch's own insertion range so
 * the text the user had before and after the paste stays editable, it holds the stream back behind a confirmed break
 * instead of painting one key at a time, and it settles into the same paste summary the bracketed path produces.
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

    /**
     * A text key that arrives this soon after the previous one is machine paced: no keyboard repeats that fast, so the
     * batch can only be a paste the host is streaming. It is the moment the composer stops painting the batch.
     */
    static final Duration STREAM_INTERVAL = Duration.ofMillis(50);

    private static final String TAB_TEXT = "    ";
    private static final long IDLE_INTERVAL_NANOS = IDLE_INTERVAL.toNanos();
    private static final long STREAM_INTERVAL_NANOS = STREAM_INTERVAL.toNanos();

    private final int start;
    private final StringBuilder held = new StringBuilder();
    private int end;
    private long lastKeyNanos;
    private boolean previousKeyKnown;
    private boolean endsWithCr;
    private boolean paste;

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

    /** Whether the batch is proven paste content, so a following break belongs to it instead of submitting. */
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

    /** Whether a text key arriving now is machine paced, which proves the batch is a streamed paste. */
    boolean streamedAt(long nowNanos) {
        return previousKeyKnown && nowNanos - lastKeyNanos <= STREAM_INTERVAL_NANOS;
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
     * Moves the text the composer already painted into the held range, so the first line of a streamed paste stops
     * being visible while the rest of it arrives. The range still starts where the batch started, so the draft the
     * user had before the paste keeps its own text and the settle still replaces only the batch.
     */
    void retractLiveText(String liveText, long nowNanos) {
        held.append(liveText);
        end = start;
        paste = true;
        lastKeyNanos = nowNanos;
        previousKeyKnown = true;
    }

    /** Holds one key's text back until the batch settles. */
    void hold(String text, boolean breakFromCr, long nowNanos) {
        held.append(text);
        this.endsWithCr = breakFromCr;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }

    /** Consumes the LF half of a CRLF pair whose CR half already held the one break. */
    void consumeCrLfHalf(long nowNanos) {
        this.endsWithCr = false;
        this.lastKeyNanos = nowNanos;
        this.previousKeyKnown = true;
    }
}
