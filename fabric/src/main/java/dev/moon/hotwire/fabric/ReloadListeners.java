package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.version.ReloadBands;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.registration.Fields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

final class ReloadListeners {
    private ReloadListeners() {
    }

    static boolean runWithoutHeavy(Minecraft game, Runnable reload) {
        return runDropping(game, one -> ReloadBands.HEAVY_LISTENERS.stream().anyMatch(type -> type.isInstance(one)),
                () -> OngoingReload.runPartial(reload));
    }

    static boolean runWithout(Minecraft game, Class<?> type, Runnable reload) {
        return runDropping(game, type::isInstance, reload);
    }

    private static boolean runDropping(Minecraft game, Predicate<PreparableReloadListener> dropped, Runnable reload) {
        List<PreparableReloadListener> listeners = findListeners(game);
        if (listeners == null) {
            return false;
        }
        List<PreparableReloadListener> all = List.copyOf(listeners);
        try {
            listeners.removeIf(dropped);
            reload.run();
        } finally {
            listeners.clear();
            listeners.addAll(all);
        }
        listSoundsAgain(game);
        return true;
    }

    @SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
    private static void listSoundsAgain(Minecraft game) {
        SoundManager sounds = game.getSoundManager();
        ResourceManager packs = game.getResourceManager();
        for (FieldSlots.Slot field : Fields.getDeclared(SoundManager.class)) {
            try {
                if (!field.isStatic() && field.get(sounds) instanceof Map<?, ?> files && !files.isEmpty()
                        && files.values().iterator().next() instanceof Resource) {
                    Map<Object, Object> list = (Map<Object, Object>) files;
                    CompletableFuture.supplyAsync(() -> Sound.SOUND_LISTER.listMatchingResources(packs))
                            .thenAcceptAsync(listed -> {
                                list.putAll(listed);
                                list.keySet().retainAll(listed.keySet());
                            }, game);
                    return;
                }
            } catch (IllegalAccessException e) {
            }
        }
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
