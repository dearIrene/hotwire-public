package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.fabric.add.FailedAddJars;
import dev.moon.hotwire.fabric.add.LivePlugins;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.switching.SwitchApplier;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

public final class GameClasses implements SwitchApplier.Game {
    private final Map<String, byte[]> updatedConfigs = new ConcurrentHashMap<>();
    private final KnotInternals knot;
    private final ClassLoader game;
    private final Map<String, SoftReference<byte[]>> originals = new ConcurrentHashMap<>();

    public GameClasses(KnotInternals knot, ClassLoader game) {
        this.knot = knot;
        this.game = game;
    }

    @Override
    public @Nullable Class<?> findLoaded(String internalName) {
        if (!this.knot.isLoaded(internalName)) {
            return null;
        }
        try {
            return Class.forName(internalName.replace('/', '.'), false, this.game);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public @Nullable Class<?> findLoadedNamed(String name) {
        Class<?> type = name.contains("/") ? null : this.findLoaded(name.replace('.', '/'));
        if (type != null || !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun."))) {
            return type;
        }
        try {
            return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @Override
    public boolean hasLivePlugin(String config) {
        return LivePluginShapes.has(config);
    }

    @Override
    public @Nullable Set<String> findPluginAnswers(String config) {
        return LivePlugins.findAnswers(config);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isRegistryEntry(Object value) {
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            if (((Registry<Object>) registry).getResourceKey(value).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public void forgetOriginalBytes() {
        this.originals.clear();
    }

    @Override
    public byte @Nullable [] findOriginalBytes(String internalName) {
        SoftReference<byte[]> retained = this.originals.get(internalName);
        byte[] bytes = retained == null ? null : retained.get();
        if (bytes == null) {
            bytes = this.knot.findPreMixinBytes(internalName, this.findLoaded(internalName));
            if (bytes != null) {
                this.originals.put(internalName, new SoftReference<>(bytes));
            }
        }
        return FailedAddJars.removeHiddenInterfaces(bytes);
    }

    @Override
    public void useConfigs(Map<String, byte[]> configs) {
        this.updatedConfigs.putAll(configs);
    }

    @Override
    public byte @Nullable [] findConfigBytes(String config) {
        byte[] updated = this.updatedConfigs.get(config);
        if (updated != null) {
            return updated;
        }
        try (InputStream in = this.game.getResourceAsStream(config)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String getSide() {
        return FabricLoader.getInstance().getEnvironmentType().name();
    }

    private static final int LONG_LIVED_DEPTH = 4;
    private static final List<Class<?>> WORLD_OBJECTS = List.of(Entity.class, BlockEntity.class, Level.class,
            ChunkAccess.class, ChunkHolder.class, ChunkMap.class, LevelChunkSection.class, ChunkSource.class,
            DistanceManager.class, ChunkTracker.class, IOWorker.class, LevelLightEngine.class, MinecraftServer.class,
            ServerGamePacketListenerImpl.class, ClientPacketListener.class, MultiPlayerGameMode.class);

    private static final List<Class<?>> WORLD_ROOTS = List.of(Level.class, MinecraftServer.class,
            ClientPacketListener.class, MultiPlayerGameMode.class, Entity.class);

    @Override
    public boolean isWorldObject(Class<?> type) {
        for (Class<?> root : WORLD_OBJECTS) {
            if (root.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    public boolean isReachedFromWorld(String mod, List<String> frames) {
        for (String frame : frames) {
            int paren = frame.indexOf('(');
            String method = paren < 0 ? frame : frame.substring(0, paren);
            int slash = method.lastIndexOf('/');
            int dot = method.lastIndexOf('.');
            if (dot <= slash) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(method.substring(slash + 1, dot), false, this.game);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (!mod.equals(GameRegistrations.findModOf(type))) {
                return this.isWorldObject(type);
            }
        }
        return false;
    }

    public static List<Object> findLongLived() {
        Minecraft client = Minecraft.getInstance();
        List<Object> found = new ArrayList<>(List.of(client));
        found.addAll(Fields.findBelow(client, LONG_LIVED_DEPTH, type -> (type.getName().startsWith("net.minecraft.")
                || type.getName().startsWith("com.mojang.")) && !isWorldRoot(type)));

        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            found.add(connection);
        }
        return found;
    }

    private static boolean isWorldRoot(Class<?> type) {
        for (Class<?> root : WORLD_ROOTS) {
            if (root.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> getStartupMethods(String internalName) {
        return switch (internalName) {
            case "com/mojang/blaze3d/systems/RenderSystem" -> Set.of("initRenderer");
            //? if >=26.1 {
            case "net/minecraft/client/Minecraft" -> Set.of("onResourceLoadFinished", "onGameLoadFinished",
                    "buildInitialScreens");
            //?} else {
            /*case "net/minecraft/class_310" -> Set.of("method_53465", "method_51736", "method_53527");
            *///?}
            default -> Set.of();
        };
    }

    public static Set<String> getJoinMethods(String internalName) {
        if (Minecraft.getInstance().getConnection() == null) {
            return Set.of();
        }
        //? if >=26.1 {
        return internalName.equals("net/minecraft/client/multiplayer/ClientPacketListener") ? Set.of("handleLogin")
                : Set.of();
        //?} else {
        /*return internalName.equals("net/minecraft/class_634") ? Set.of("method_11120") : Set.of();
        *///?}
    }

    @Override
    public boolean isInWorld() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null || client.getSingleplayerServer() != null;
    }
}
