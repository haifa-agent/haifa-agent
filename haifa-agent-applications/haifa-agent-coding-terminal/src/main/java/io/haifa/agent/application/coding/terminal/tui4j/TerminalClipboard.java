package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.term.Clipboard;

/**
 * Copies selected transcript text to the host clipboard. Production delegates to tui4j's local
 * clipboard (AWT/CLI); tests inject a controllable implementation.
 */
interface TerminalClipboard {
    boolean copy(String text);

    static TerminalClipboard system() {
        return Clipboard::tryCopy;
    }
}
