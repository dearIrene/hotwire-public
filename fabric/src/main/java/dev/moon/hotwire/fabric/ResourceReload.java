package dev.moon.hotwire.fabric;

//? if <1.21.5 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}
import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.ModelReloadCheck;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceReload {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceReload.class);

    private static final Set<String> PACKED = ConcurrentHashMap.newKeySet();

    private static volatile boolean reloadedWhilePaused;

    private static volatile boolean reloadedRenderers;

    private static volatile Set<String> packsAtPause = Set.of();

    private static final Set<String> MODELS_AT_PAUSE = ConcurrentHashMap.newKeySet();
    private static final Set<String> FONTS_AT_PAUSE = ConcurrentHashMap.newKeySet();

    private static volatile List<String> comingOn = List.of();

    private static volatile List<String> awaitingAssets = List.of();
    private static final Duration WAIT = Duration.ofMinutes(2);
    private static final Duration PAUSED_WAIT = Duration.ofSeconds(30);

    private static final List<Class<?>> RENDERERS = List.of(EntityRenderer.class, BlockEntityRenderer.class,
            EntityModel.class);

    private static final List<Class<?>> SHORT_LIVED = List.of(Screen.class, Particle.class, SoundInstance.class);
    private static final List<Class<?>> RESOURCES = List.of(FontSet.class, FontTexture.class, AbstractTexture.class,
            ModelManager.class);
    private static final List<Class<?>> LANGUAGE = List.of(LanguageManager.class, ClientLanguage.class);

    private static final List<Class<?>> RELOADED = List.of(MultiPackResourceManager.class, WeighedSoundEvents.class);

    private static final List<Class<?>> FONTS = List.of(FontSet.class, FontTexture.class);

    private ResourceReload() {
    }

    static @Nullable String describePaused(Collection<String> newlyBuilt, boolean reloadAsked) {
        List<Class<?>> types = findLoaded(newlyBuilt);
        if (reloadAsked || hasAny(types, RELOADED)) {
            return "Reloading textures and models (the game stops for a moment)";
        }
        return hasAny(types, RENDERERS) ? "Rebuilding its renderers (the game stops for a moment)" : null;
    }

    static void rebuildRenderers(Collection<String> newlyBuilt, Collection<String> rebuilt, boolean reloadAsked) {
        List<Class<?>> types = findLoaded(newlyBuilt);
        boolean renderers = hasAny(types, RENDERERS);
        boolean reload = reloadAsked || renderers || hasAny(types, RELOADED);
        boolean screens = hasAny(types, List.of(Screen.class));
        boolean particles = hasAny(types, List.of(Particle.class));
        boolean sounds = hasAny(types, List.of(SoundInstance.class));
        boolean language = hasAny(findLoaded(rebuilt), LANGUAGE);
        if (!renderers && !screens && !particles && !sounds && !language && !reload) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        try {
            GameHold.onGameBeforeNextFrame(() -> {
                awaitOngoingReload(game);

                boolean renderersOnly = !reloadAsked && !hasAny(types, RELOADED);
                if (reload && !reloadedWhilePaused && !(renderersOnly && reloadedRenderers)) {
                    reloadWhilePaused(game, renderersOnly);
                }
                if (particles) {
                    game.particleEngine.setLevel(game.level);
                }
                if (sounds) {
                    game.getSoundManager().stop();
                }
                if (language) {
                    game.getLanguageManager().onResourceManagerReload(game.getResourceManager());
                }
                if (screens) {
                    ModViews.reopenScreen();
                }
            }, game).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            List<String> made = new ArrayList<>();
            if (renderers) {
                made.add("renderers");
            }
            if (screens) {
                made.add("screens");
            }
            if (particles) {
                made.add("particles");
            }
            if (sounds) {
                made.add("sounds");
            }
            if (language) {
                made.add("language tables");
            }
            if (reload) {
                made.add("resources");
            }
            LOGGER.info("[Hotwire] the switch changed how {} are built; they were made again before the next frame",
                    String.join(", ", made));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the renderers could not be made again; F3+T makes them", e);
        }
    }

    static void reloadForRenderers(boolean whole) {
        Minecraft game = Minecraft.getInstance();
        GameHold.onGameAndWait(() -> {
            awaitOngoingReload(game);
            reloadWhilePaused(game, !whole);
        });
    }

    public static void reloadBeforeFrame(String why) {
        Minecraft game = Minecraft.getInstance();
        if (reloadedWhilePaused) {
            LOGGER.info("[Hotwire] {}: the reload that made the game's objects again brought them", why);
            return;
        }
        if (awaitOngoingReload(game)) {
            LOGGER.info("[Hotwire] {}: a reload already under way brought them", why);
            return;
        }
        LOGGER.info("[Hotwire] {}; the game reloads its resources before its next frame", why);
        reloadWhilePaused(game, false);
    }

    public static void forgetPausedReload() {
        reloadedWhilePaused = false;
        reloadedRenderers = false;
        packsAtPause = Set.of();
        MODELS_AT_PAUSE.clear();
        FONTS_AT_PAUSE.clear();
    }

    static void onComingOn(List<String> mods) {
        comingOn = List.copyOf(mods);
    }

    static void startUnprofiled(Runnable start) {
        String manager = ReloadableResourceManager.class.getName();
        Level was = LogManager.getLogger(manager).getLevel();
        if (!was.isLessSpecificThan(Level.DEBUG)) {
            start.run();
            return;
        }
        Configurator.setLevel(manager, Level.INFO);
        try {
            start.run();
        } finally {
            Configurator.setLevel(manager, was);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static void reloadWhilePaused(Minecraft game, boolean renderersOnly) {
        long began = System.nanoTime();
        Tripwire.release(comingOn);

        comingOn.forEach(LoaderModList::show);
        packsAtPause = getAvailablePacks();
        noteOwnersAtPause();

        LoadingOverlay shown = ModViews.findLoadingOverlay();
        ReloadInstance last = shown == null ? null : findReloadOf(shown);
        if (last != null && last.isDone()) {
            ModViews.closeOverlay();
        }
        boolean isWhole = !renderersOnly || !ReloadListeners.runWithoutHeavy(game,
                () -> startUnprofiled(game::reloadResourcePacks));
        if (isWhole) {
            startUnprofiled(game::reloadResourcePacks);
        }
        LoadingOverlay loading = ModViews.findLoadingOverlay();
        ReloadInstance reload = loading == null ? null : findReloadOf(loading);
        if (reload == null) {
            LOGGER.warn("[Hotwire] the resource reload has no loading overlay to wait on; it runs with the game");
            return;
        }
        awaitWhilePaused(game, reload);
        if (reload.isDone() && ReloadFailures.findFailure(reload) != null) {
            reload = runAgainWhilePaused(game, reload);
            isWhole = true;
        }
        reloadedWhilePaused |= isWhole;
        reloadedRenderers |= !isWhole;
        if (reload == null) {
            return;
        }
        if (!reload.isDone()) {
            LOGGER.warn("[Hotwire] the resource reload held the game for {} s; it ends with the game drawing",
                    PAUSED_WAIT.toSeconds());
            return;
        }
        Throwable failure = ReloadFailures.findFailure(reload);
        if (failure != null) {
            LOGGER.error("[Hotwire] the resource reload run again under the hold failed as well; the game answers it"
                    + " as it answers any", failure);
            return;
        }
        LOGGER.info("[Hotwire] the resources were reloaded with the game held in {} ms{}",
                (System.nanoTime() - began) / 1_000_000, isWhole ? "" : ", for the renderers alone");
    }

    private static void awaitWhilePaused(Minecraft game, ReloadInstance reload) {
        long deadline = System.nanoTime() + PAUSED_WAIT.toNanos();
        game.managedBlock(() -> {
            //? if <1.21.5 {
            /*RenderSystem.replayQueue();
            *///?}
            return reload.isDone() || System.nanoTime() > deadline;
        });
    }

    private static @Nullable ReloadInstance runAgainWhilePaused(Minecraft game, ReloadInstance failed) {
        Set<String> off = LoaderModList.getHidden();
        LOGGER.warn("[Hotwire] the resource reload under the hold failed; it runs again in whole",
                ReloadFailures.findFailure(failed));
        ReloadedLists.takeOutUnmade(off);
        LoadingOverlay overlay = ModViews.findLoadingOverlay();
        ReloadInstance again = overlay == null ? null : ReloadFailures.runAgain(game, overlay, off);
        if (again != null) {
            awaitWhilePaused(game, again);
        }
        return again;
    }

    private static void noteOwnersAtPause() {
        Set<String> hidden = LoaderModList.getHidden();
        if (hidden.isEmpty() || !comingOn.isEmpty() || !awaitingAssets.isEmpty()) {
            return;
        }
        try {
            MODELS_AT_PAUSE.addAll(ModelReloadCheck.findModelOwners(hidden));
            FontManager fonts = findFontManager();
            if (fonts != null) {
                FONTS_AT_PAUSE.addAll(ModelReloadCheck.findFontOwners(fonts, hidden));
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] what {} have in the game's models and fonts could not be read before the reload",
                    hidden, e);
        }
    }

    private static boolean awaitOngoingReload(Minecraft game) {
        BooleanSupplier done = findOngoingReload(game);
        if (done == null) {
            return false;
        }
        long deadline = System.nanoTime() + WAIT.toNanos();
        if (game.isSameThread()) {
            game.managedBlock(() -> done.getAsBoolean() || System.nanoTime() > deadline);
        } else {
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
        if (done.getAsBoolean()) {
            LOGGER.info("[Hotwire] a reload under way was waited for");
        } else {
            LOGGER.warn("[Hotwire] a reload under way was waited for, and did not end");
        }
        return true;
    }

    private static @Nullable BooleanSupplier findOngoingReload(Minecraft game) {
        List<String> seen = new ArrayList<>();
        for (FieldSlots.Slot field : Fields.getDeclared(Minecraft.class)) {
            if (!field.isStatic() && CompletableFuture.class.isAssignableFrom(field.getType())) {
                try {
                    Object value = field.get(game);
                    String state = "null";
                    if (value instanceof CompletableFuture<?> future) {
                        state = future.isDone() ? "done" : "pending";
                        if (!future.isDone()) {
                            return future::isDone;
                        }
                    }
                    seen.add(field.getName() + "=" + state);
                } catch (IllegalAccessException e) {
                    seen.add(field.getName() + "=closed");
                }
            }
        }
        LoadingOverlay loading = ModViews.findLoadingOverlay();
        ReloadInstance ongoing = loading == null ? null : findReloadOf(loading);
        if (ongoing == null || ongoing.isDone()) {
            LOGGER.info("[Hotwire] no reload under way: futures {}, overlay {}", seen, loading);
            return null;
        }
        return ongoing::isDone;
    }

    static @Nullable ReloadInstance findReloadOf(LoadingOverlay overlay) {
        for (FieldSlots.Slot field : Fields.getDeclared(LoadingOverlay.class)) {
            if (ReloadInstance.class.isAssignableFrom(field.getType())) {
                try {
                    return (ReloadInstance) field.get(overlay);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    static void reloadResources(Collection<String> newlyBuilt, Collection<String> mods, boolean off) {
        Set<String> available = getAvailablePacks();
        if (off) {
            Set<String> atPause = packsAtPause;
            mods.stream().filter(id -> available.contains(id) || atPause.contains(id)).forEach(PACKED::add);

            ModelReloadCheck.noteModels(mods.stream().filter(MODELS_AT_PAUSE::contains).toList());
            ModelReloadCheck.noteFonts(mods.stream().filter(FONTS_AT_PAUSE::contains).toList());
        }
        List<String> packsGone = off ? List.of()
                : mods.stream().filter(PACKED::remove).filter(id -> !available.contains(id)).toList();

        boolean modelsAtOn = !off && ModelReloadCheck.bakeAtOn(mods);
        boolean fontsAtOn = !off && ModelReloadCheck.hasFontsAtOn(mods);
        if (reloadedWhilePaused) {
            return;
        }
        boolean models = off ? ModelReloadCheck.hasModelsOf(mods) : modelsAtOn;
        FontManager manager = findFontManager();
        boolean fonts = manager != null && (off ? ModelReloadCheck.hasFontsOf(manager, mods) : fontsAtOn);

        boolean ownPack = off && hasPackOf(mods);
        List<Class<?>> resources = findLoaded(newlyBuilt).stream()
                .filter(type -> RESOURCES.stream().anyMatch(root -> root.isAssignableFrom(type))).toList();
        String changed = "the switch changed how " + resources.stream().map(Class::getSimpleName).toList()
                + " are built";
        if (!resources.isEmpty() && !models && packsGone.isEmpty() && !ownPack
                && resources.stream().allMatch(type -> hasAny(List.of(type), FONTS))) {
            reloadFonts(changed, List.of());
        } else if (!resources.isEmpty()) {
            run(changed);
        } else if (models) {
            run(off ? "the game's baked models held " + mods + "'s" : mods + " bake their models in again");
        } else if (fonts && packsGone.isEmpty() && !ownPack) {
            reloadFonts(off ? "the game's fonts held " + mods + "'s" : mods + " make their fonts again",
                    off ? mods : List.of());
        } else if (fonts) {
            run("the game's fonts held " + mods + "'s, read through their own pack", mods);
        } else if (!packsGone.isEmpty()) {
            run(packsGone + "'s resource packs come back, which a reload while they were off left out");
        }
    }

    static boolean hasPackGone(Collection<String> mods) {
        Set<String> available = getAvailablePacks();
        Set<String> atPause = packsAtPause;
        return mods.stream().anyMatch(id -> (PACKED.contains(id) || atPause.contains(id))
                && !available.contains(id));
    }

    private static boolean hasPackOf(Collection<String> mods) {
        return Minecraft.getInstance().getResourceManager().listPacks().anyMatch(pack -> {
            String mod = GameRegistrations.findModOf(pack.getClass());
            return mod != null && mods.contains(mod);
        });
    }

    private static void layOutChat() {
        Minecraft game = Minecraft.getInstance();
        //? if >=26.2 {
        game.execute(() -> game.gui.hud.getChat().rescaleChat());
        //?} else {
        /*game.execute(() -> game.gui.getChat().rescaleChat());
        *///?}
    }

    private static void reportFailed(String why, Set<String> chosen) {
        if (ModViews.findLoadingOverlay() != null) {
            LOGGER.warn("[Hotwire] the resource reload ({}) did not finish in {} s; F3+T makes another", why,
                    WAIT.toSeconds());
            logReloadThreads();
            return;
        }
        Set<String> now = getChosenPacks();
        Set<String> off = LoaderModList.getHidden();
        List<String> dropped = chosen.stream().filter(id -> !now.contains(id) && !off.contains(id)).sorted()
                .toList();
        LOGGER.error("[Hotwire] the resource reload ({}) failed, and the game {}", why, dropped.isEmpty()
                ? "kept the packs the player chose" : "dropped the packs the player chose: " + dropped);
    }

    private static Set<String> getChosenPacks() {
        try {
            return Set.copyOf(Minecraft.getInstance().getResourcePackRepository().getSelectedIds());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private static Set<String> getAvailablePacks() {
        try {
            return Set.copyOf(Minecraft.getInstance().getResourcePackRepository().getAvailableIds());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    static boolean isRebuiltWhilePaused(Class<?> type) {
        return hasAny(List.of(type), RENDERERS) || hasAny(List.of(type), SHORT_LIVED)
                || hasAny(List.of(type), RELOADED) || ClientLanguage.class.isAssignableFrom(type)
                || Model.class.isAssignableFrom(type);
    }

    private static List<Class<?>> findLoaded(Collection<String> names) {
        ClassLoader game = ResourceReload.class.getClassLoader();
        List<Class<?>> types = new ArrayList<>();
        for (String name : names) {
            try {
                types.add(Class.forName(name.replace('/', '.'), false, game));
            } catch (ClassNotFoundException | LinkageError e) {
            }
        }
        return types;
    }

    private static boolean hasAny(List<Class<?>> types, List<Class<?>> roots) {
        return types.stream().anyMatch(type -> roots.stream().anyMatch(root -> root.isAssignableFrom(type)));
    }

    private static void reloadFonts(String why, Collection<String> closing) {
        Minecraft game = Minecraft.getInstance();
        PreparableReloadListener fonts = findFontManager();
        if (fonts == null) {
            run(why);
            return;
        }
        LOGGER.info("[Hotwire] {}; the game makes its fonts again", why);
        long began = System.nanoTime();
        try {
            Executor applying = task -> game.execute(() -> Tripwire.expect(closing, task));
            SimpleReloadInstance.create(game.getResourceManager(), List.of(fonts), ForkJoinPool.commonPool(),
                    applying, CompletableFuture.completedFuture(Unit.INSTANCE), false).done().get(WAIT.toMillis(),
                    TimeUnit.MILLISECONDS);
            LOGGER.info("[Hotwire] the fonts were made again in {} ms", (System.nanoTime() - began) / 1_000_000);
            layOutChat();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the fonts could not be made again ({}); the game reloads its resources", why, e);
            run(why);
        }
    }

    private static @Nullable FontManager findFontManager() {
        Minecraft game = Minecraft.getInstance();
        for (FieldSlots.Slot field : Fields.getDeclared(Minecraft.class)) {
            try {
                if (FontManager.class.isAssignableFrom(field.getType()) && field.get(game) instanceof FontManager one) {
                    return one;
                }
            } catch (IllegalAccessException e) {
            }
        }
        return null;
    }

    public static void awaitAssets(List<String> mods) {
        awaitingAssets = List.copyOf(mods);
    }

    public static void endAssetWait() {
        awaitingAssets = List.of();
        forgetPausedReload();
    }

    public static boolean isAwaitingAssets() {
        return !awaitingAssets.isEmpty();
    }

    public static void noteRecoverySkipped(Exception cause) {
        LOGGER.info("[Hotwire] {} asked for a resource before the reload that brings their assets: {}", awaitingAssets,
                cause.getMessage());
    }

    public static void run(String why) {
        run(why, List.of());
    }

    private static void run(String why, Collection<String> closing) {
        if (awaitOngoingReload(Minecraft.getInstance())) {
            LOGGER.info("[Hotwire] {}: a reload already under way brought them", why);
            return;
        }
        LOGGER.info("[Hotwire] {}; the game reloads its resources", why);
        Minecraft game = Minecraft.getInstance();
        Set<String> chosen = getChosenPacks();
        try {
            CompletableFuture<Void> begun = new CompletableFuture<>();
            CompletableFuture<Void> reload = CompletableFuture.completedFuture(game).thenComposeAsync(on -> {
                List<CompletableFuture<Void>> started = new ArrayList<>();
                Tripwire.expect(closing, () -> started.add(on.reloadResourcePacks()));
                begun.complete(null);
                return started.getFirst();
            }, game);
            begun.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            long deadline = System.nanoTime() + WAIT.toNanos();
            while (ModViews.findLoadingOverlay() != null && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            if (!reload.isDone()) {
                reportFailed(why, chosen);
                return;
            }
            layOutChat();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[Hotwire] the resource reload ({}) did not begin in {} s", why, WAIT.toSeconds(), e);
            logReloadThreads();
        }
    }

    private static void logReloadThreads() {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (thread.getName().equals("Render thread") || thread.getName().startsWith("Worker-")) {
                LOGGER.warn("[Hotwire] {} at {}", thread.getName(), Arrays.stream(stack).limit(12)
                        .map(String::valueOf).collect(Collectors.joining(" < ")));
            }
        });
    }
}
