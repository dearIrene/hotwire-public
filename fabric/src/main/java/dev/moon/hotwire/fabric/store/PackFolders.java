package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PackFolders implements StoreModel.Packs {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackFolders.class);

    private final ModrinthClient client;

    private final BiConsumer<String, Boolean> say;

    private final Map<ModrinthClient.Kind, Map<String, String>> files = new EnumMap<>(ModrinthClient.Kind.class);

    private final Set<Path> read = ConcurrentHashMap.newKeySet();
    private final Set<String> downloading = ConcurrentHashMap.newKeySet();

    public PackFolders(ModrinthClient client, BiConsumer<String, Boolean> say) {
        this.client = client;
        this.say = say;
        for (ModrinthClient.Kind kind : ModrinthClient.Kind.values()) {
            this.files.put(kind, new ConcurrentHashMap<>());
        }
    }

    @Override
    public StoreModel.PackState find(ModrinthClient.Kind kind, String projectId) {
        if (this.downloading.contains(kind + projectId)) {
            return StoreModel.PackState.DOWNLOADING;
        }
        Path folder = findFolder(kind);
        if (folder == null) {
            return StoreModel.PackState.NEEDS_WORLD;
        }
        this.readOnce(kind, folder);
        String name = this.findFiles(kind).get(projectId);
        if (name == null || !Files.exists(folder.resolve(name))) {
            return StoreModel.PackState.MISSING;
        }
        return isOn(kind, name) ? StoreModel.PackState.ON : StoreModel.PackState.OFF;
    }

    private Map<String, String> findFiles(ModrinthClient.Kind kind) {
        Map<String, String> map = this.files.get(kind);
        if (map == null) {
            throw new IllegalArgumentException(kind.toString());
        }
        return map;
    }

    private static @Nullable Path findFolder(ModrinthClient.Kind kind) {
        Minecraft game = Minecraft.getInstance();
        return switch (kind) {
            case RESOURCE_PACK -> game.getResourcePackDirectory();
            case SHADER -> game.gameDirectory.toPath().resolve("shaderpacks");
            case DATA_PACK -> {
                MinecraftServer server = game.getSingleplayerServer();
                yield server == null ? null : server.getWorldPath(LevelResource.DATAPACK_DIR);
            }
            case MOD -> game.gameDirectory.toPath().resolve("mods");
        };
    }

    private void readOnce(ModrinthClient.Kind kind, Path folder) {
        if (!this.read.add(folder)) {
            return;
        }
        Thread.ofPlatform().name("hotwire-store-packs").daemon().start(() -> {
            List<Path> zips;
            try (Stream<Path> listed = Files.list(folder)) {
                zips = listed.filter(path -> path.toString().endsWith(".zip")).toList();
            } catch (IOException e) {
                return;
            }
            if (zips.isEmpty()) {
                return;
            }
            try {
                Map<String, String> hashes = FileHashes.findSha512ByName(zips);
                this.client.findByHashes(hashes).forEach((name, version) -> this.findFiles(kind)
                        .put(version.projectId(), name));
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not read {}: {}", folder, e.toString());
                this.read.remove(folder);
            }
        });
    }

    @Override
    public void fetch(ModrinthClient.Kind kind, String projectId, String versionId, String title) {
        Path folder = findFolder(kind);
        if (folder == null) {
            this.say.accept(Lang.get("hotwire.store.pack.needs_world"), true);
            return;
        }
        if (!this.downloading.add(kind + projectId)) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        Thread.ofPlatform().name("hotwire-store-fetch").daemon().start(() -> {
            try {
                ModrinthClient.Version version = this.client.findVersions(kind, projectId).stream()
                        .filter(one -> versionId.isEmpty() || one.id().equals(versionId)).findFirst()
                        .orElseThrow(() -> new IOException(Lang.get("hotwire.store.no_version", title)));
                byte[] bytes = this.client.fetch(version);
                String name = version.fileName().replaceAll("[^A-Za-z0-9._+ ()-]", "_");
                Files.createDirectories(folder);
                AtomicFiles.write(folder.resolve(name), bytes);
                this.findFiles(kind).put(projectId, name);
                game.execute(() -> this.say.accept(Lang.get("hotwire.store.pack.downloaded", title), false));
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the store could not get {}: {}", title, e.toString());
                game.execute(() -> this.say.accept(Lang.get("hotwire.store.download_failed", title,
                        String.valueOf(e.getMessage())), true));
            } finally {
                this.downloading.remove(kind + projectId);
            }
        });
    }

    @Override
    public void toggle(ModrinthClient.Kind kind, String projectId) {
        String name = this.findFiles(kind).get(projectId);
        if (name == null) {
            return;
        }
        boolean on = !isOn(kind, name);
        try {
            switch (kind) {
                case RESOURCE_PACK -> toggleResourcePack(name, on);
                case SHADER -> toggleShader(name, on);
                case DATA_PACK -> toggleDataPack(name, on);
                case MOD -> {
                    return;
                }
            }
            this.say.accept(Lang.get(on ? "hotwire.store.pack.turned_on" : "hotwire.store.pack.turned_off", name),
                    false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] {} could not be switched: {}", name, e.toString());
            this.say.accept(kind == ModrinthClient.Kind.SHADER && findIris() == null
                    ? Lang.get("hotwire.store.pack.shader_needs_iris")
                    : Lang.get("hotwire.store.pack.failed", name, String.valueOf(e.getMessage())), true);
        }
    }

    private static boolean isOn(ModrinthClient.Kind kind, String name) {
        Minecraft game = Minecraft.getInstance();
        return switch (kind) {
            case RESOURCE_PACK -> game.getResourcePackRepository().getSelectedIds().contains("file/" + name);
            case DATA_PACK -> {
                MinecraftServer server = game.getSingleplayerServer();
                yield server != null && server.getPackRepository().getSelectedIds().contains("file/" + name);
            }
            case SHADER -> isShaderOn(name);
            case MOD -> false;
        };
    }

    private static void toggleResourcePack(String name, boolean on) {
        Minecraft game = Minecraft.getInstance();
        PackRepository packs = game.getResourcePackRepository();
        packs.reload();
        String id = "file/" + name;
        boolean changed = on ? packs.addPack(id) : packs.removePack(id);
        if (!changed) {
            throw new IllegalStateException("the game has no pack " + id);
        }
        game.options.updateResourcePacks(packs);
    }

    private static void toggleDataPack(String name, boolean on) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("no world is open");
        }
        String id = "file/" + name;
        server.execute(() -> {
            PackRepository packs = server.getPackRepository();
            packs.reload();
            List<String> chosen = new ArrayList<>(packs.getSelectedIds());
            if (on) {
                chosen.add(id);
            } else {
                chosen.remove(id);
            }
            server.reloadResources(chosen).exceptionally(failure -> {
                LOGGER.info("[Hotwire] the data packs did not reload: {}", failure.toString());
                return null;
            });
        });
    }

    private static @Nullable Class<?> findIris() {
        try {
            return Class.forName("net.irisshaders.iris.Iris", false, PackFolders.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Object findIrisConfig() throws ReflectiveOperationException {
        Class<?> iris = findIris();
        return iris == null ? null : iris.getMethod("getIrisConfig").invoke(null);
    }

    private static boolean isShaderOn(String name) {
        try {
            Object config = findIrisConfig();
            if (config == null || !Boolean.TRUE.equals(config.getClass().getMethod("areShadersEnabled")
                    .invoke(config))) {
                return false;
            }
            return config.getClass().getMethod("getShaderPackName").invoke(config) instanceof Optional<?> chosen
                    && chosen.map(name::equals).orElse(false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void toggleShader(String name, boolean on) throws ReflectiveOperationException {
        Class<?> iris = findIris();
        Object config = findIrisConfig();
        if (iris == null || config == null) {
            throw new IllegalStateException("Iris is not installed");
        }
        if (on) {
            config.getClass().getMethod("setShaderPackName", String.class).invoke(config, name);
        }
        config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, on);
        config.getClass().getMethod("save").invoke(config);
        iris.getMethod("reload").invoke(null);
    }
}
