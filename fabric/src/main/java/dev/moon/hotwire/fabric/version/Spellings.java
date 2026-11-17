package dev.moon.hotwire.fabric.version;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.moon.hotwire.fabric.mixin.core.client.gui.components.toasts.ToastManagerAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jspecify.annotations.Nullable;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
*///?}
//? if >=1.21.11 {
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.fabric.impl.resource.pack.ModNioPackResources;
//?} else {
/*import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.fabric.impl.resource.loader.ModNioResourcePack;
*///?}
//? if <1.21.8 {
/*import net.minecraft.client.gui.screens.TitleScreen;
*///?}
//? if >=26.3 {
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
//?} else if >=1.21.5 {
/*import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
*///?}
//? if <1.21.5 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}

public final class Spellings {
    //? if >=26.1 {
    public static final String MINECRAFT_CLASS = "net/minecraft/client/Minecraft";
    public static final Set<String> LOAD_FINISHED_METHODS = Set.of("onResourceLoadFinished", "onGameLoadFinished",
            "buildInitialScreens");
    public static final String PACKET_LISTENER_CLASS = "net/minecraft/client/multiplayer/ClientPacketListener";
    public static final Set<String> JOIN_METHODS = Set.of("handleLogin");
    //?} else {
    /*public static final String MINECRAFT_CLASS = "net/minecraft/class_310";
    public static final Set<String> LOAD_FINISHED_METHODS = Set.of("method_53465", "method_51736", "method_53527");
    public static final String PACKET_LISTENER_CLASS = "net/minecraft/class_634";
    public static final Set<String> JOIN_METHODS = Set.of("method_11120");
    *///?}

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

    public static ClickEvent makeCommandClick(String command) {
        //? if >=1.21.5 {
        return new ClickEvent.RunCommand(command);
        //?} else {
        /*return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        *///?}
    }

    public static boolean isShippedUnderOtherNames() {
        //? if >=26.1 {
        return false;
        //?} else {
        /*return true;
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

    public static <T> @Nullable T findRegistryValue(Registry<T> registry, ResourceKey<T> key) {
        //? if >=1.21.2 {
        return registry.getValue(key);
        //?} else {
        /*return registry.get(key);
        *///?}
    }

    public static ChatComponent getChat() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.hud.getChat();
        //?} else {
        /*return Minecraft.getInstance().gui.getChat();
        *///?}
    }

    public static long getWindowHandle() {
        //? if >=1.21.9 {
        return Minecraft.getInstance().getWindow().handle();
        //?} else {
        /*return Minecraft.getInstance().getWindow().getWindow();
        *///?}
    }

    public static void runOnServerSoon(MinecraftServer server, TickTask task) {
        //? if >=1.21.2 {
        server.schedule(task);
        //?} else {
        /*server.tell(task);
        *///?}
    }

    public static void setRendererLevel(Minecraft game, @Nullable ClientLevel level) {
        //? if >=26.2 {
        game.levelExtractor.setLevel(level);
        //?} else {
        /*game.levelRenderer.setLevel(level);
        *///?}
    }

    public static void markAllChanged(Minecraft game) {
        //? if >=26.2 {
        game.levelExtractor.allChanged();
        //?} else {
        /*game.levelRenderer.allChanged();
        *///?}
    }

    public static void redrawChunks(Minecraft game, ClientLevel level) {
        //? if >=26.2 {
        game.levelExtractor.allChanged();
        //?} else {
        /*int reach = (game.options.getEffectiveRenderDistance() + 1) * 16;
        if (game.player == null) {
            return;
        }
        int x = game.player.getBlockX();
        int z = game.player.getBlockZ();
        int bottom = level.dimensionType().minY();
        game.levelRenderer.setBlocksDirty(x - reach, bottom, z - reach, x + reach,
                bottom + level.dimensionType().height() - 1, z + reach);
        *///?}
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> makeCommandLiteral(String name) {
        //? if >=26.1 {
        return ClientCommands.literal(name);
        //?} else {
        /*return ClientCommandManager.literal(name);
        *///?}
    }

    public static void registerTooltips(Function<TooltipComponent, @Nullable ClientTooltipComponent> finder) {
        //? if >=26.1 {
        ClientTooltipComponentCallback.EVENT.register(finder::apply);
        //?} else {
        /*TooltipComponentCallback.EVENT.register(finder::apply);
        *///?}
    }

    public static Object getLevelEvent(boolean load) {
        //? if >=26.1 {
        return load ? ServerLevelEvents.LOAD : ServerLevelEvents.UNLOAD;
        //?} else {
        /*return load ? ServerWorldEvents.LOAD : ServerWorldEvents.UNLOAD;
        *///?}
    }

    public static void runLevelListener(Object listener, MinecraftServer server, ServerLevel level, boolean load) {
        //? if >=26.1 {
        if (load) {
            ((ServerLevelEvents.Load) listener).onLevelLoad(server, level);
        } else {
            ((ServerLevelEvents.Unload) listener).onLevelUnload(server, level);
        }
        //?} else {
        /*if (load) {
            ((ServerWorldEvents.Load) listener).onWorldLoad(server, level);
        } else {
            ((ServerWorldEvents.Unload) listener).onWorldUnload(server, level);
        }
        *///?}
    }

    @SuppressWarnings("NullAway")
    public static @Nullable Object makeHiddenPack(String id, ModContainer container, @Nullable String subPath,
            PackType type) {
        //? if >=1.21.11 {
        return ModNioPackResources.create(id, container, subPath, type, PackActivationType.ALWAYS_ENABLED, true);
        //?} else {
        /*return ModNioResourcePack.create(id, container, subPath, type, ResourcePackActivationType.ALWAYS_ENABLED,
                true);
        *///?}
    }

    public static boolean hasRenderQueue() {
        //? if <1.21.5 {
        /*return true;
        *///?} else {
        return false;
        //?}
    }

    public static void replayRenderQueue() {
        //? if <1.21.5 {
        /*RenderSystem.replayQueue();
        *///?}
    }

    public static void leaveWorld(Minecraft client) {
        //? if >=1.21.10 {
        client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        //?} elif >=1.21.8 {
        /*ClientLevel level = client.level;
        if (level != null) {
            level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        client.disconnectWithSavingScreen();
        *///?} else {
        /*ClientLevel level = client.level;
        if (level != null) {
            level.disconnect();
        }
        client.disconnect(new TitleScreen());
        *///?}
    }

    public static boolean isCompiledPipeline(Class<?> type) {
        //? if >=1.21.5 {
        return CompiledRenderPipeline.class.isAssignableFrom(type);
        //?} else {
        /*return false;
        *///?}
    }
}
