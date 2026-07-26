package io.haifa.agent.sandbox.localnative;

import java.util.Locale;

final class LocalNativeAdapters {
    private LocalNativeAdapters() {}

    static LocalNativeAdapter system() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return new MacSeatbeltAdapter();
        if (os.contains("linux")) return new LinuxBubblewrapAdapter();
        return new UnsupportedLocalNativeAdapter(os.isBlank() ? "unknown" : os);
    }
}
