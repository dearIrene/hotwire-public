package dev.moon.hotwire.fabric;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.engine.ModSoundFiles;
import dev.moon.hotwire.fabric.registration.ContentRemovals;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.ModelReloadCheck;
import dev.moon.hotwire.fabric.registration.ReloadedLists;
import dev.moon.hotwire.fabric.screen.HoldFrame;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.report.FreezeDump;
import dev.moon.hotwire.ui.Lang;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceReload {
    private static final int FIRST_TEXTURE_UNIT = 0x84C0;
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceReload.class);
    private static final int LAYOUT_TRIES = 3;

    private static volatile boolean reloadedWhilePaused;

    private static volatile boolean leavingReload;
    private static volatile @Nullable String waitingReload;

    private static volatile boolean reloadedRenderers;

    private static volatile boolean wholeAfterHold;

    private static volatile long holdBegan = Long.MAX_VALUE;

    private static volatile Set<String> packsAtPause = Set.of();

    private static volatile List<String> comingOn = List.of();
    private static volatile List<String> goingOff = List.of();
    private static volatile List<String> added = List.of();

    private static volatile List<String> awaitingAssets = List.of();
    static final Duration WAIT = Duration.ofMinutes(2);
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

    static @Nullable String describePaused(Collection<String> newlyBuilt, Collection<String> reloadFor) {
        List<Class<?>> types = findLoaded(newlyBuilt);
        if ((!reloadFor.isEmpty() && !isMadeAsReloadBegins(reloadFor)) || hasAny(types, RELOADED)) {
            return Lang.get("hotwire.progress.reloading_models");
        }
        return hasAny(types, RENDERERS) ? Lang.get("hotwire.progress.rebuilding_renderers") : null;
    }

    static void closeWorldRenderer(Collection<String> newlyBuilt) {
        if (WorldRendererReset.holdsAny(findLoaded(newlyBuilt))) {
            GameHold.onGameAndWait(WorldRendererReset::close);
        }
    }

    private static void resetTextureUnit(String internalName) {
        try {
            Class.forName(internalName.replace('/', '.'), false, ResourceReload.class.getClassLoader())
                    .getMethod("_activeTexture", int.class).invoke(null, FIRST_TEXTURE_UNIT);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("[Hotwire] the texture unit in use stays as it is: {}", e.toString());
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static void rebuildRenderers(Collection<String> newlyBuilt, Collection<String> rebuilt,
            Collection<String> reloadFor) {
        boolean reloadAsked = !reloadFor.isEmpty();
        boolean packsAlone = reloadAsked && isMadeAsReloadBegins(reloadFor);
        List<Class<?>> types = findLoaded(newlyBuilt);
        boolean renderers = hasAny(types, RENDERERS);
        boolean reload = reloadAsked || renderers || hasAny(types, RELOADED);
        boolean screens = hasAny(types, List.of(Screen.class));
        boolean particles = hasAny(types, List.of(Particle.class));
        boolean sounds = hasAny(types, List.of(SoundInstance.class));
        boolean language = hasAny(findLoaded(rebuilt), LANGUAGE);
        boolean world = WorldRendererReset.isClosed() || WorldRendererReset.holdsAny(types);

        String unitsOf = rebuilt.stream().filter(name -> name.endsWith("/GlStateManager")).findFirst()
                .orElse(null);
        if (!renderers && !screens && !particles && !sounds && !language && !reload && !world && unitsOf == null) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        try {
            GameHold.onGameBeforeNextFrame(() -> {
                if (unitsOf != null) {
                    resetTextureUnit(unitsOf);
                }

                GameParts.getOngoingReload().awaitBegun(game);
                if (world) {
                    WorldRendererReset.run();
                    HoldFrame.drawIfDue();
                }

                boolean renderersOnly = !reloadAsked && !hasAny(types, RELOADED);
                if (reload && !reloadedWhilePaused && !(renderersOnly && reloadedRenderers)) {
                    if (!reloadAsked && !comingOn.isEmpty()) {
                        reloadedRenderers = true;
                        reloadedWhilePaused |= !renderersOnly;
                        List<String> soundsFrom = findSwitched();
                        GameParts.getBackgroundReload().beginAfterHold(() -> {
                            Runnable start = () -> startUnprofiled(() -> game.reloadResourcePacks());
                            if (!renderersOnly || wholeAfterHold || !ReloadListeners.runWithoutHeavy(game, start)) {
                                startWhole(game, soundsFrom);
                            }
                        });
                    } else {
                        reloadWhilePaused(game, renderersOnly, packsAlone);
                    }
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
            if (world) {
                made.add("the world's renderer");
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

    @SuppressWarnings("FutureReturnValueIgnored")
    static void reloadForRenderers(boolean whole) {
        Minecraft game = Minecraft.getInstance();
        if (!whole) {
            if (leavingReload) {
                waitingReload = "the off before left the renderers as they were";
                LOGGER.info("[Hotwire] the off's reload for the renderers is left to the switch that follows it");
                return;
            }

            reloadedRenderers = true;
            List<String> soundsFrom = findSwitched();
            GameParts.getBackgroundReload().beginAfterHold(() -> {
                Runnable start = () -> startUnprofiled(() -> game.reloadResourcePacks());
                if (wholeAfterHold || !ReloadListeners.runWithoutHeavy(game, start)) {
                    startWhole(game, soundsFrom);
                }
            });
            return;
        }
        GameHold.onGameAndWait(() -> {
            GameParts.getOngoingReload().awaitBegun(game);
            reloadWhilePaused(game, false, false);
        });
    }

    public static void reloadBeforeFrame(String why, boolean wordsAlone, List<String> adding) {
        Minecraft game = Minecraft.getInstance();
        added = List.copyOf(adding);
        if (reloadedWhilePaused) {
            LOGGER.info("[Hotwire] {}: the reload that made the game's objects again brought them", why);
            return;
        }
        if (GameParts.getOngoingReload().awaitBegun(game)) {
            LOGGER.info("[Hotwire] {}: a reload already under way brought them", why);
            return;
        }
        if (wordsAlone && WordsReload.reload(game)) {
            return;
        }
        LOGGER.info("[Hotwire] {}; the game reloads its resources before its next frame", why);
        reloadWhilePaused(game, false, false);
    }

    public static void leaveReloadToNext(boolean leaves) {
        leavingReload = leaves;
    }

    public static void runWaitingReload() {
        String why = waitingReload;
        if (why != null) {
            run(why);
        }
    }

    public static void forgetPausedReload() {
        FreezeDump.onSwitchEnded();
        ShaderPrograms.forget();
        reloadedWhilePaused = false;
        reloadedRenderers = false;
        wholeAfterHold = false;
        holdBegan = Long.MAX_VALUE;
        added = List.of();
        packsAtPause = Set.of();
        GameParts.getModelCheck().forgetPause();
    }

    static void onComingOn(List<String> mods) {
        comingOn = List.copyOf(mods);
    }

    static void restoreTrimmed(Collection<String> mods) {
        if (!GameParts.getPacks().isTrimmedListed(mods)) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        LOGGER.info("[Hotwire] the packs of {} come back whole with a reload after the hold, the game drawing", mods);
        wholeAfterHold = true;
        List<String> soundsFrom = findSwitched();
        GameParts.getBackgroundReload().beginAfterHold(() -> startWhole(game, soundsFrom));
    }

    public static void awaitIdle() {
        Minecraft game = Minecraft.getInstance();
        if (!game.isSameThread() && !GameHold.isUnbudgeted()) {
            GameParts.getOngoingReload().awaitIdle(game);
        }
    }

    static void onGoingOff(List<String> mods) {
        goingOff = List.copyOf(mods);
    }

    private static List<String> findSwitched() {
        List<String> switched = new ArrayList<>(comingOn);
        switched.addAll(goingOff);
        switched.addAll(added);
        return switched;
    }

    @SuppressWarnings("FutureReturnValueIgnored")

    private static void startWhole(Minecraft game, List<String> switched) {
        wholeAfterHold = false;
        waitingReload = null;
        Runnable start = () -> startUnprofiled(() -> game.reloadResourcePacks());
        if (ModSoundFiles.isAnyIn(switched) || !ReloadListeners.runWithout(game, SoundManager.class, start)) {
            start.run();
        }
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
    private static void reloadWhilePaused(Minecraft game, boolean renderersOnly, boolean packsAlone) {
        long began = System.nanoTime();
        if (!renderersOnly && !packsAlone) {
            waitingReload = null;
        }
        Tripwire.release(comingOn);

        comingOn.forEach(LoaderModList::show);
        packsAtPause = getAvailablePacks();
        holdBegan = System.nanoTime();
        noteOwnersAtPause();

        GameParts.getOngoingReload().clearDoneOverlay();

        if (comingOn.isEmpty()) {
            reloadedWhilePaused = true;

            if (awaitingAssets.isEmpty()) {
                ShaderPrograms.compileAgain(game);
            }
            if (leavingReload) {
                waitingReload = "the off before left the game's resources as they were";
                LOGGER.info("[Hotwire] the off's resource reload is left to the switch that follows it");
                return;
            }
            List<String> soundsFrom = findSwitched();
            GameParts.getBackgroundReload().beginAfterHold(() -> startWhole(game, soundsFrom));
            return;
        }

        if (packsAlone && GameParts.getBackgroundReload().beginNow(() -> startWhole(game, findSwitched()))) {
            reloadedWhilePaused = true;
            LOGGER.info("[Hotwire] the reload began with the game held and ends with it drawing: only packs made"
                    + " without a mod's setup waited for it");
            return;
        }
        LOGGER.info("[Hotwire] a reload with the game held, for what the switch's mods make in it");
        OngoingReload.Run run = OngoingReload.begin(() -> {
            if (!renderersOnly || !ReloadListeners.runWithoutHeavy(game,
                    () -> startUnprofiled(() -> game.reloadResourcePacks()))) {
                startWhole(game, findSwitched());
            }
        });
        if (run == null) {
            LOGGER.warn("[Hotwire] the game put the resource reload off behind its loading overlay; it runs with the"
                    + " game");
            return;
        }
        boolean isWhole = !run.partial;
        ReloadInstance reload = run.reload;
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
        if (isWhole && wholeAfterHold) {
            GameParts.getBackgroundReload().dropDue();
            wholeAfterHold = false;
        }
    }

    private static void awaitWhilePaused(Minecraft game, ReloadInstance reload) {
        awaitWhilePaused(game, reload::isDone);
    }

    static void awaitWhilePaused(Minecraft game, BooleanSupplier done) {
        long deadline = System.nanoTime() + PAUSED_WAIT.toNanos();
        game.managedBlock(() -> {
            Spellings.replayRenderQueue();
            HoldFrame.drawIfDue();
            return done.getAsBoolean() || System.nanoTime() > deadline;
        });
    }

    private static @Nullable ReloadInstance runAgainWhilePaused(Minecraft game, ReloadInstance failed) {
        Set<String> off = LoaderModList.getHidden();
        LOGGER.warn("[Hotwire] the resource reload under the hold failed; it runs again in whole",
                ReloadFailures.findFailure(failed));
        ReloadedLists.takeOutUnmade(off);
        LoadingOverlay overlay = ModViews.findLoadingOverlay();
        ReloadInstance again = overlay == null ? null : GameParts.getReloadFailures().runAgain(game, overlay, off);
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
            GameParts.getModelCheck().noteAtPause(hidden, FontReload.findFontManager());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] what {} have in the game's models and fonts could not be read before the reload",
                    hidden, e);
        }
    }

    static void reloadResources(Collection<String> newlyBuilt, Collection<String> mods, boolean off,
            Set<String> packed) {
        try {
            reloadResourcesNow(newlyBuilt, mods, off, packed);

            if (!hasPackOf(mods)) {
                OffWords.loadAgain();
            }
        } finally {
            ModelReloadCheck.forgetAhead();
        }
    }

    private static void reloadResourcesNow(Collection<String> newlyBuilt, Collection<String> mods, boolean off,
            Set<String> packed) {
        ModelReloadCheck owners = GameParts.getModelCheck();
        Set<String> available = getAvailablePacks();
        if (off) {
            Set<String> atPause = packsAtPause;
            mods.stream().filter(id -> available.contains(id) || atPause.contains(id)).forEach(packed::add);

            owners.rememberAtPause(mods);
        }
        List<String> packsGone = off ? List.of()
                : mods.stream().filter(packed::remove).filter(id -> !available.contains(id)).toList();

        boolean modelsAtOn = !off && owners.bakeAtOn(mods);
        boolean fontsAtOn = !off && owners.hasFontsAtOn(mods);
        if (reloadedWhilePaused) {
            return;
        }

        if (off && GameParts.getOngoingReload().hasWholeEndedSince(holdBegan)) {
            LOGGER.info("[Hotwire] a whole reload since the hold made the game's resources without {}", mods);
            return;
        }

        boolean models = (off ? owners.hasModelsOf(mods) : modelsAtOn)
                || (!off && mods.stream().anyMatch(ContentRemovals::isBack));
        FontManager manager = FontReload.findFontManager();
        boolean fonts = manager != null && (off ? owners.hasFontsOf(manager, mods) : fontsAtOn);

        boolean ownPack = off && hasPackOf(mods);
        List<Class<?>> resources = findLoaded(newlyBuilt).stream()
                .filter(type -> RESOURCES.stream().anyMatch(root -> root.isAssignableFrom(type))).toList();
        String changed = "the switch changed how " + resources.stream().map(Class::getSimpleName).toList()
                + " are built";
        if (off && leavingReload && (!resources.isEmpty() || models || fonts)) {
            waitingReload = "the off before left the game's resources holding " + mods + "'s";
            LOGGER.info("[Hotwire] the off's resource reload is left to the switch that follows it");
            return;
        }
        if (!resources.isEmpty() && !models && packsGone.isEmpty() && !ownPack
                && resources.stream().allMatch(type -> hasAny(List.of(type), FONTS))) {
            FontReload.reloadFonts(changed, List.of());
        } else if (!resources.isEmpty()) {
            run(changed);
        } else if (models && off && ModelReloadCheck.findModelOwners(mods).isEmpty()) {
            LOGGER.info("[Hotwire] the game's baked models held {}'s, each drawing what it wraps now: they are made"
                    + " anew with the game drawing", mods);
            List<String> soundsFrom = findSwitched();
            GameParts.getBackgroundReload().beginAfterHold(() -> startWhole(Minecraft.getInstance(), soundsFrom));
        } else if (models) {
            run(off ? "the game's baked models held " + mods + "'s" : mods + " bake their models in again");
        } else if (fonts && packsGone.isEmpty() && !ownPack) {
            FontReload.reloadFonts(off ? "the game's fonts held " + mods + "'s" : mods + " make their fonts again",
                    off ? mods : List.of());
        } else if (fonts) {
            run("the game's fonts held " + mods + "'s, read through their own pack", mods);
        } else if (!packsGone.isEmpty()) {
            run(packsGone + "'s resource packs come back, which a reload while they were off left out");
        }
    }

    static boolean hasPackGone(Collection<String> mods, Set<String> packed) {
        Set<String> available = getAvailablePacks();
        Set<String> atPause = packsAtPause;
        List<String> gone = mods.stream().filter(id -> (packed.contains(id) || atPause.contains(id))
                && !available.contains(id)).toList();
        if (!gone.isEmpty()) {
            LOGGER.info("[Hotwire] the packs of {} left with a reload since their off: a reload brings them back",
                    gone);
        }
        return !gone.isEmpty();
    }

    private static boolean hasPackOf(Collection<String> mods) {
        return Minecraft.getInstance().getResourceManager().listPacks().anyMatch(pack -> {
            String mod = Owners.findModOf(pack.getClass());
            return mod != null && mods.contains(mod);
        });
    }

    public static void layOutChat() {
        for (int tries = 0; tries < LAYOUT_TRIES; tries++) {
            try {
                Spellings.getChat().rescaleChat();
                return;
            } catch (ConcurrentModificationException e) {
                LOGGER.info("[Hotwire] another thread wrote to the chat as it was laid out");
            }
        }
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

    private static boolean isMadeAsReloadBegins(Collection<String> names) {
        List<Class<?>> types = findLoaded(names);
        return types.size() == names.size() && types.stream().allMatch(PackResources.class::isAssignableFrom);
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

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void startAgain(Minecraft game) {
        startUnprofiled(() -> game.reloadResourcePacks());
    }

    public static void awaitAssets(List<String> mods) {
        awaitingAssets = List.copyOf(mods);
    }

    @SuppressWarnings("ReferenceEquality")
    public static void endAssetWait() {
        List<String> ending = awaitingAssets;
        forgetPausedReload();
        Minecraft.getInstance().execute(() -> GameParts.getBackgroundReload().runAfterReload(() -> {
            if (awaitingAssets == ending) {
                awaitingAssets = List.of();
            }
        }));
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

    static void run(String why, Collection<String> closing) {
        waitingReload = null;

        Minecraft game = Minecraft.getInstance();
        if (GameParts.getOngoingReload().awaitIdle(game)) {
            LOGGER.info("[Hotwire] {}: a reload already under way brought them", why);
            return;
        }
        LOGGER.info("[Hotwire] {}; the game reloads its resources", why);
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

            GameParts.getOngoingReload().awaitIdle(game);
            if (!reload.isDone()) {
                reportFailed(why, chosen);
                return;
            }
            Minecraft.getInstance().execute(ResourceReload::layOutChat);
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
