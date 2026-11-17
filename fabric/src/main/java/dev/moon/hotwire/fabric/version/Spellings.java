package dev.moon.hotwire.fabric.version;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastManagerAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}

public final class Spellings {
    private Spellings() {
    }

    public static @Nullable Screen openScreen() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen();
        //?} else {
        /*return Minecraft.getInstance().screen;
        *///?}
    }

    public static @Nullable Overlay openOverlay() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.overlay();
        //?} else {
        /*return Minecraft.getInstance().getOverlay();
        *///?}
    }

    public static Object getScreenHolder() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui;
        //?} else {
        /*return Minecraft.getInstance();
        *///?}
    }

    public static void showOverlay(@Nullable Overlay overlay) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setOverlay(overlay);
        //?} else {
        /*Minecraft.getInstance().setOverlay(overlay);
        *///?}
    }

    public static void resize(Screen screen) {
        //? if >=1.21.11 {
        screen.resize(screen.width, screen.height);
        //?} else {
        /*screen.resize(Minecraft.getInstance(), screen.width, screen.height);
        *///?}
    }

    public static void show(@Nullable Screen screen) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen);
        //?} else {
        /*Minecraft.getInstance().setScreen(screen);
        *///?}
    }

    public static ToastManagerAccessor getToasts() {
        Minecraft minecraft = Minecraft.getInstance();
        //? if >=26.2 {
        return (ToastManagerAccessor) minecraft.gui.toastManager();
        //?} else if >=1.21.4 {
        /*return (ToastManagerAccessor) minecraft.getToastManager();
        *///?} else {
        /*return (ToastManagerAccessor) minecraft.getToasts();
        *///?}
    }

    public static void tellPlayer(LocalPlayer player, Component text) {
        //? if >=26.1 {
        player.sendSystemMessage(text);
        //?} else {
        /*player.displayClientMessage(text, false);
        *///?}
    }

    public static void runSoon(Runnable task) {
        Minecraft client = Minecraft.getInstance();
        //? if >=1.21.2 {
        client.schedule(task);
        //?} else {
        /*client.tell(task);
        *///?}
    }

    public static List<KeyMapping> registerKeys(Identifier id, Map<String, Integer> defaults) {
        //? if >=1.21.10 {
        KeyMapping.Category category = KeyMapping.Category.register(id);
        //?} else {
        /*String category = "key.category." + id.toLanguageKey();
        *///?}
        List<KeyMapping> made = new ArrayList<>();
        for (Map.Entry<String, Integer> key : defaults.entrySet()) {
            made.add(new KeyMapping(key.getKey(), key.getValue(), category));
        }
        for (KeyMapping key : made) {
            //? if >=26.1 {
            KeyMappingHelper.registerKeyMapping(key);
            //?} else {
            /*KeyBindingHelper.registerKeyBinding(key);
            *///?}
        }
        return made;
    }

    public static void setKey(KeyMapping mapping, int code) {
        //? if >=26.3 {
        mapping.setKey(InputConstants.Type.KEYBOARD.getOrCreate(code));
        //?} else {
        /*mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(code));
        *///?}
    }

    public static Identifier getKeyId(ResourceKey<?> key) {
        //? if >=1.21.11 {
        return key.identifier();
        //?} else {
        /*return key.location();
        *///?}
    }
}
