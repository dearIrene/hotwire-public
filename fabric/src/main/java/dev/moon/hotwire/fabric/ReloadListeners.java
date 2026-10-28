package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.redefine.FieldSlots;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
//? if <26.1 {
/*import net.minecraft.client.renderer.LevelRenderer;
*///?}
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.language.LanguageManager;
//? if <26.1 {
/*import net.minecraft.client.resources.model.ModelManager;
*///?}
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.jspecify.annotations.Nullable;

final class ReloadListeners {
    //? if >=26.1 {
    private static final List<Class<?>> BESIDE_RENDERERS = List.of(SoundManager.class, FontManager.class,
            LanguageManager.class, TextureManager.class);
    //?} else {
    /*private static final List<Class<?>> BESIDE_RENDERERS = List.of(ModelManager.class, SoundManager.class,
            FontManager.class, LanguageManager.class, TextureManager.class, LevelRenderer.class);
    *///?}

    private ReloadListeners() {
    }

    static boolean runWithoutHeavy(Minecraft game, Runnable reload) {
        List<PreparableReloadListener> listeners = findListeners(game);
        if (listeners == null) {
            return false;
        }
        List<PreparableReloadListener> all = List.copyOf(listeners);
        try {
            listeners.removeIf(one -> BESIDE_RENDERERS.stream().anyMatch(type -> type.isInstance(one)));
            reload.run();
        } finally {
            listeners.clear();
            listeners.addAll(all);
        }
        return true;
    }

    static boolean runWithout(Minecraft game, Class<?> type, Runnable reload) {
        List<PreparableReloadListener> listeners = findListeners(game);
        if (listeners == null) {
            return false;
        }
        List<PreparableReloadListener> all = List.copyOf(listeners);
        try {
            listeners.removeIf(type::isInstance);
            reload.run();
        } finally {
            listeners.clear();
            listeners.addAll(all);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<PreparableReloadListener> findListeners(Minecraft game) {
        Object manager = game.getResourceManager();
        for (FieldSlots.Slot field : Fields.getDeclared(manager.getClass())) {
            try {
                if (!field.isStatic() && field.get(manager) instanceof ArrayList<?> list && !list.isEmpty()
                        && list.getFirst() instanceof PreparableReloadListener) {
                    return (List<PreparableReloadListener>) list;
                }
            } catch (IllegalAccessException e) {
            }
        }
        return null;
    }
}
