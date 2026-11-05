package dev.moon.hotwire.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.moon.hotwire.jvm.FamilySlices;

public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        String slice = FamilySlices.findRunning();
        if (slice != null && slice.isEmpty()) {
            return parent -> null;
        }
        if (slice != null && !slice.equals(ModMenuEntry.class.getPackageName())) {
            try {
                return Class.forName(slice + ".ModMenuEntry").asSubclass(ModMenuApi.class).getConstructor()
                        .newInstance().getModConfigScreenFactory();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Hotwire's slice " + slice + " has no Mod Menu entry", e);
            }
        }

        return parent -> HotwireClient.getEngine().getScreens().makeScreen(parent);
    }
}
