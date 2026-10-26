package dev.moon.hotwire.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            HotwireEngine engine = HotwireClient.findEngine();
            return engine == null ? parent : engine.makeScreen(parent);
        };
    }
}
