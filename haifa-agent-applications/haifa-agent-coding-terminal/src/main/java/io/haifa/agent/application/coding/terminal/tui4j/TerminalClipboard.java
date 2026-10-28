package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.term.Clipboard;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Host clipboard access for transcript copy and Ctrl+V paste into the editor. Production copy
 * delegates to tui4j's local clipboard (AWT/CLI). Paste reads via AWT only so this module never
 * spawns clipboard helper processes. Tests inject a controllable implementation.
 */
interface TerminalClipboard {
    boolean copy(String text);

    Optional<String> read();

    static TerminalClipboard system() {
        return new SystemClipboard();
    }

    final class SystemClipboard implements TerminalClipboard {
        private static final Logger LOG = Logger.getLogger(SystemClipboard.class.getName());

        @Override
        public boolean copy(String text) {
            return Clipboard.tryCopy(text);
        }

        @Override
        public Optional<String> read() {
            if (Boolean.getBoolean("tui4j.clipboard.disabled")) {
                return Optional.empty();
            }
            try {
                if (GraphicsEnvironment.isHeadless()) {
                    return Optional.empty();
                }
                var transferable = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                if (transferable == null || !transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    return Optional.empty();
                }
                Object value = transferable.getTransferData(DataFlavor.stringFlavor);
                if (!(value instanceof String text) || text.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(text);
            } catch (Throwable failure) {
                LOG.log(Level.FINE, "AWT clipboard read failed", failure);
                return Optional.empty();
            }
        }
    }
}
