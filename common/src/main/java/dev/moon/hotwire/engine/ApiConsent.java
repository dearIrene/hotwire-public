package dev.moon.hotwire.engine;

import dev.moon.hotwire.ui.Lang;
import org.jspecify.annotations.Nullable;

final class ApiConsent {
    private ApiConsent() {
    }

    static @Nullable String findRefusal(HotwireEngine engine, boolean downloads) {
        String key = findRefusalKey(engine.isFirstOpenAccepted(), engine.isDownloadsWarned(), downloads);
        return key == null ? null : Lang.get(key);
    }

    static @Nullable String findRefusalKey(boolean accepted, boolean warned, boolean downloads) {
        if (!accepted) {
            return "hotwire.api.add.no_prompt";
        }
        return downloads && !warned ? "hotwire.api.no_downloads_warning" : null;
    }
}
