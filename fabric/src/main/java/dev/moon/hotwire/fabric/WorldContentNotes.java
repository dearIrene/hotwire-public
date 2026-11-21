package dev.moon.hotwire.fabric;

import dev.moon.hotwire.engine.DataPackFaults;
import dev.moon.hotwire.engine.HotwireEngine;
import dev.moon.hotwire.engine.WorldContentRecord;
import dev.moon.hotwire.fabric.registration.WorldContent;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldContentNotes {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldContentNotes.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());
    private static final int MOST_SAID = 2;
    private static final Set<String> GAME_ITSELF = Set.of("minecraft", "java", "fabricloader");
    private static final int MARGIN = 20;

    private static volatile @Nullable String opening;

    private static volatile WeakReference<Object> logged = new WeakReference<>(null);

    private WorldContentNotes() {
    }

    public static void onOpenWorld(String level) {
        opening = level;
    }

    public static void noteAtStart(MinecraftServer server, @Nullable HotwireEngine engine) {
        opening = null;
        if (!server.isSingleplayer()) {
            return;
        }
        List<String> on = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();

            if ((engine == null || engine.isOn(id)) && !GAME_ITSELF.contains(id)) {
                on.add(id);
            }
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        List<WorldContentRecord.Entry> content = new ArrayList<>();
        WorldContent.find(on).keySet().forEach(id -> content.add(new WorldContentRecord.Entry(id, findNameOf(id),
                findVersionOf(id), today)));
        FabricLoader loader = FabricLoader.getInstance();
        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            WorldContentRecord.note(world, content, openHistory(String.valueOf(world.getFileName())),
                    id -> loader.isModLoaded(id) ? (engine != null && !engine.isOn(id) ? "turned off in Hotwire"
                            : "on, but its content is no longer in the game") : "not installed");
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the world's mods could not be written into it", e);
        }
    }

    @SuppressWarnings("ReferenceEquality")

    public static @Nullable MultiLineTextWidget makeCause(Object screen, int width, int top) {
        HotwireEngine engine = HotwireClient.findEngine();
        List<String> all = findCauses(engine);
        if (all.isEmpty()) {
            return null;
        }
        if (logged.get() != screen) {
            logged = new WeakReference<>(screen);
            LOGGER.warn("[Hotwire] the world {} would not load. The cause, as Hotwire knows it: {}", opening,
                    String.join(" ", all));
            openHistory(String.valueOf(opening)).addRefused(String.join(" ", all));
        }
        List<String> said = all.subList(0, Math.min(MOST_SAID, all.size()));
        Minecraft client = Minecraft.getInstance();
        MultiLineTextWidget text = new MultiLineTextWidget(Component.literal(String.join("\n", said))
                .withStyle(ChatFormatting.RED), client.font).setMaxWidth(width - 2 * MARGIN).setCentered(true);
        text.setX((width - text.getWidth()) / 2);
        text.setY(top);
        return text;
    }

    private static List<String> findCauses(@Nullable HotwireEngine engine) {
        List<String> said = new ArrayList<>();
        String broke = DataPackFaults.findRecentMod(id -> FabricLoader.getInstance().isModLoaded(id));
        if (broke != null) {
            said.add(Lang.get("hotwire.world_fail.broken", findNameOf(broke)));
        }
        String level = opening;
        if (level == null) {
            return said;
        }
        Path world = Minecraft.getInstance().getLevelSource().getBaseDir().resolve(level);
        FabricLoader loader = FabricLoader.getInstance();
        for (WorldContentRecord.Entry gone : WorldContentRecord.findMissing(world,
                id -> loader.isModLoaded(id) && (engine == null || engine.isOn(id)))) {
            String key = loader.isModLoaded(gone.id()) ? "hotwire.world_fail.off" : "hotwire.world_fail.gone";
            said.add(Lang.get(key, DAY.format(gone.lastSeen()), gone.name()));
        }
        return said;
    }

    static WorldContentRecord.History openHistory(String world) {
        return new WorldContentRecord.History(FabricLoader.getInstance().getGameDir().resolve("hotwire")
                .resolve(WorldContentRecord.History.FILE), world);
    }

    private static String findVersionOf(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getVersion()
                .getFriendlyString()).orElse("?");
    }

    private static String findNameOf(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getName()).orElse(id);
    }
}
