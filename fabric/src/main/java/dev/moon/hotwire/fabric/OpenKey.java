package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class OpenKey {
    private static final Identifier CATEGORY = Identifier.fromNamespaceAndPath(HotwireClient.ID,
            HotwireClient.ID);
    private static @Nullable KeyMapping key;
    private static Runnable open = () -> { };

    private OpenKey() {
    }

    static void register(Runnable opener) {
        KeyMapping made = make();
        //? if >=26.1 {
        KeyMappingHelper.registerKeyMapping(made);
        //?} else {
        /*KeyBindingHelper.registerKeyBinding(made);
        *///?}
        open = opener;
        key = made;
    }

    public static void onPressed() {
        KeyMapping made = key;
        if (made != null && made.consumeClick()) {
            open.run();
        }
    }

    private static KeyMapping make() {
        //? if >=1.21.10 {
        return new KeyMapping("key.hotwire.open", InputConstants.KEY_H, KeyMapping.Category.register(CATEGORY));
        //?} else {
        /*return new KeyMapping("key.hotwire.open", InputConstants.KEY_H, "key.category." + CATEGORY.toLanguageKey());
        *///?}
    }
}
