package io.haifa.agent.application.coding.terminal.jline;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

/** Fixed keyboard semantics for the single full-screen Terminal input owner. */
public final class JLineKeyBindings {
    private static final long ESCAPE_AMBIGUOUS_TIMEOUT_MILLIS = 100L;

    private JLineKeyBindings() {}

    static KeyMap<Binding> create(Terminal terminal) {
        KeyMap<Binding> keys = new KeyMap<>();
        keys.setUnicode(Binding.SELF_INSERT);
        keys.setNomatch(Binding.SELF_INSERT);
        keys.setAmbiguousTimeout(ESCAPE_AMBIGUOUS_TIMEOUT_MILLIS);

        keys.bind(Binding.SUBMIT, "\r", "\n");
        keys.bind(Binding.FOLLOW_UP, KeyMap.alt('\r'));
        keys.bind(Binding.CANCEL_OR_CLOSE, KeyMap.esc());
        keys.bind(Binding.RESTORE, "\033[1;3A");
        keys.bind(Binding.TOGGLE, KeyMap.ctrl('O'));
        keys.bind(Binding.INTERRUPT, KeyMap.ctrl('C'));
        keys.bind(Binding.EOF, KeyMap.ctrl('D'));
        keys.bind(Binding.NEWLINE, "\033[13;2u", "\033[27;2;13~");

        keys.bind(Binding.BACKSPACE, KeyMap.del(), KeyMap.ctrl('H'));
        keys.bind(Binding.DELETE, "\033[3~");
        keys.bind(Binding.LEFT, "\033[D");
        keys.bind(Binding.RIGHT, "\033[C");
        keys.bind(Binding.UP, "\033[A");
        keys.bind(Binding.DOWN, "\033[B");
        keys.bind(Binding.HOME, "\033[H", "\033[1~");
        keys.bind(Binding.END, "\033[F", "\033[4~");
        keys.bind(Binding.KILL_TO_START, KeyMap.ctrl('U'));
        keys.bind(Binding.KILL_TO_END, KeyMap.ctrl('K'));
        keys.bind(Binding.KILL_PREVIOUS_WORD, KeyMap.ctrl('W'));
        keys.bind(Binding.COMPLETE, "\t");

        bindCapability(keys, terminal, Binding.LEFT, InfoCmp.Capability.key_left);
        bindCapability(keys, terminal, Binding.RIGHT, InfoCmp.Capability.key_right);
        bindCapability(keys, terminal, Binding.UP, InfoCmp.Capability.key_up);
        bindCapability(keys, terminal, Binding.DOWN, InfoCmp.Capability.key_down);
        bindCapability(keys, terminal, Binding.HOME, InfoCmp.Capability.key_home);
        bindCapability(keys, terminal, Binding.END, InfoCmp.Capability.key_end);
        bindCapability(keys, terminal, Binding.DELETE, InfoCmp.Capability.key_dc);
        return keys;
    }

    private static void bindCapability(
            KeyMap<Binding> keys, Terminal terminal, Binding binding, InfoCmp.Capability capability) {
        String sequence = KeyMap.key(terminal, capability);
        if (sequence != null && !sequence.isEmpty()) {
            keys.bind(binding, sequence);
        }
    }

    enum Binding {
        SELF_INSERT,
        SUBMIT,
        FOLLOW_UP,
        CANCEL_OR_CLOSE,
        RESTORE,
        TOGGLE,
        INTERRUPT,
        EOF,
        NEWLINE,
        BACKSPACE,
        DELETE,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        HOME,
        END,
        KILL_TO_START,
        KILL_TO_END,
        KILL_PREVIOUS_WORD,
        COMPLETE,
        IGNORE
    }
}
