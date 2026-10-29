package dev.moon.hotwire.fabric;

import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.add.AddSupport;
import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.fabric.rebuild.ForeignPatches;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.rebuild.GameMixinCopy;
import dev.moon.hotwire.fabric.rebuild.GeneratedClasses;
import dev.moon.hotwire.fabric.rebuild.KnotInternals;
import dev.moon.hotwire.fabric.rebuild.LivePluginShapes;
import dev.moon.hotwire.fabric.rebuild.LocalRefs;
import dev.moon.hotwire.fabric.rebuild.MixinConfigs;
import dev.moon.hotwire.fabric.rebuild.MixinInternals;
import dev.moon.hotwire.fabric.rebuild.TransformerChain;
import dev.moon.hotwire.fabric.registration.AsyncLogging;
import dev.moon.hotwire.fabric.registration.ConnectionLambdas;
import dev.moon.hotwire.fabric.registration.EventInvokers;
import dev.moon.hotwire.fabric.registration.FieldReferences;
import dev.moon.hotwire.fabric.registration.Fields;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameLibraries;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.GeneratedWrappers;
import dev.moon.hotwire.fabric.registration.GlfwCallbacks;
import dev.moon.hotwire.fabric.registration.InternedKeys;
import dev.moon.hotwire.fabric.registration.MixinPlugins;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.ModLocks;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.registration.QuietThreads;
import dev.moon.hotwire.fabric.registration.RegistryEntries;
import dev.moon.hotwire.fabric.registration.RegistryHolders;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.report.LoggedFaults;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.screen.RootScreen;
import dev.moon.hotwire.fabric.update.SwapAtClose;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.jar.ModIndex;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jvm.JarChannels;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.FinalWrites;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.InitHookReplay;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.OffWindowObjects;
import dev.moon.hotwire.redefine.ShutOverrides;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.redefine.TripwireSites;
import dev.moon.hotwire.registration.ModThreadFinder;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EngineStart {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineStart.class);
    private static final StackWalker STACK = StackWalker.getInstance();

    private static final String RELOAD_TASKS = SimpleReloadInstance.class.getName();

    private EngineStart() {
    }

    private static boolean isReloadMaking() {
        return STACK.walk(frames -> frames.anyMatch(frame -> frame.getClassName().startsWith(RELOAD_TASKS)));
    }

    private static boolean isNewer(Path file, Path than) {
        try {
            return Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(than)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    static InstalledMods readInstalledMods(FabricLoader loader) throws IOException {
        Path mods = ModsFolder.find(loader);
        ModIndex.Snapshot snapshot = new ModIndex(ForkJoinPool.commonPool()).scan(mods);
        List<ModJar> jars = new ArrayList<>();
        Map<String, Path> jarOf = new HashMap<>();
        Set<String> topLevel = new HashSet<>();
        Map<String, ModJar> disabled = new HashMap<>();
        for (ModIndex.Entry entry : snapshot.mods()) {
            if (!entry.disabled()) {
                String id = entry.jar().manifest().id();
                jars.add(entry.jar());
                topLevel.add(id);
                jarOf.put(id, entry.file());
            }
        }

        Map<String, Path> disabledFile = new HashMap<>();
        for (ModIndex.Entry entry : snapshot.mods()) {
            String id = entry.jar().manifest().id();
            if (!entry.disabled() || jarOf.containsKey(id) || loader.isModLoaded(id) || ModPolicy.isHotwire(id)) {
                continue;
            }
            Path before = disabledFile.get(id);
            if (before == null || isNewer(entry.file(), before)) {
                disabledFile.put(id, entry.file());
                disabled.put(id, entry.jar());
            }
        }
        disabledFile.forEach((id, file) -> {
            topLevel.add(id);
            jarOf.put(id, file);
        });
        for (ModContainer container : loader.getAllMods()) {
            String id = container.getMetadata().getId();
            Path jar = InstalledMods.findLoadedFrom(container, mods);
            if (jar == null || jarOf.containsKey(id) || jarOf.containsValue(jar) || !ModPolicy.isSwitchable(id)) {
                continue;
            }
            try {
                ModJar read = ModJarReader.read(jar);
                jars.add(read);
                topLevel.add(id);
                jarOf.put(id, jar);
                LOGGER.info("[Hotwire] {} runs from {}, outside the mods folder", id, jar.getFileName());
            } catch (ModJarException e) {
                LOGGER.warn("[Hotwire] {} runs from {}, which could not be read: {}", id, jar, e.getMessage());
            }
        }
        jars.replaceAll(jar -> dropUnloadedTwins(jar, loader));
        return new InstalledMods(jars, jarOf, topLevel, disabled);
    }

    private static ModJar dropUnloadedTwins(ModJar jar, FabricLoader loader) {
        Map<String, Long> count = jar.nested().stream()
                .collect(Collectors.groupingBy(nested -> nested.manifest().id(), Collectors.counting()));
        List<ModJar> nested = jar.nested().stream().filter(one -> count.getOrDefault(one.manifest().id(), 0L) == 1
                || loader.getModContainer(one.manifest().id()).map(mod -> mod.getMetadata().getVersion()
                        .getFriendlyString().equals(one.manifest().version())).orElse(true))
                .map(one -> dropUnloadedTwins(one, loader)).toList();
        return new ModJar(jar.name(), jar.manifest(), jar.patches(), nested, jar.nestedNotMods(), jar.accessWidener());
    }

    static HotwireEngine.JvmParts bind(InstalledMods installed, Progress progress, GameReports reports,
            UpdateFolders folders, UpdateQueue queue, Path gameDir, ModIcons icons) throws JvmAccessException {
        Instrumentation instrumentation = JvmAccess.getInstrumentation(findHotwireJar());
        JarChannels.open(instrumentation);
        GlfwCallbacks.useInstrumentation(instrumentation);
        LOGGER.info("[Hotwire] {} open jars read on through an interrupt", JarChannels.makeUninterruptible());
        KnotInternals knot = KnotInternals.bind();
        MixinInternals mixin = MixinInternals.bind();
        LocalRefs.prepare();
        Set<String> loadedIds = new HashSet<>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> loadedIds.add(mod.getMetadata().getId()));
        Map<String, ModMixins> mixins = MixinConfigs.withCodeAdded(
                MixinConfigs.of(installed.getGraphJars(), loadedIds), mixin.getConfigObjects());
        if (HandlerGuards.ENABLED) {
            mixins.forEach((id, mod) -> {
                if (ModPolicy.isSwitchable(id)) {
                    TripwireSites.registerClasses(id, mod.owns().stream()
                            .filter(name -> !GameLibraries.has(name, null)).collect(Collectors.toSet()));
                }
            });
        }
        GameClasses classes = new GameClasses(knot, HotwireEngine.class.getClassLoader());
        SwitchApplier applier = new SwitchApplier(
                new ClassRebuilder(instrumentation, GameMixinCopy.create(mixin, MixinConfigs.readDecorations(
                        mixins, mixin), classes::findOriginalBytes)),
                classes, mixins, mixins.keySet(), MixinConfigs.findUnowned(mixin.getConfigs(), mixins));
        applier.getRebuilder().resolveArgsWith(mixin::getArgsClassFor);
        applier.getRebuilder().getCache().cacheIn(
                CacheStore.open(findCacheFolder().resolve("mixed"), "mixed",
                        (long) CacheLimit.read(findCacheFolder()) << 20), LivePluginShapes::ask);
        applier.getRebuilder().getHooks()
                .sendGeneratedTo(new GeneratedClasses(gameDir.resolve("hotwire").resolve("generated")) ::write);
        applier.orderConfigsAs(mixin.getConfigs());
        ClassFileTransformer loadTimeMixer = applier.makeLoadTimeMixer();
        applier.holdRedefinitionWith(() -> GameHold.begin(Minecraft.getInstance())::release);
        applier.rebuildObjectsWith(ResourceReload::isRebuiltWhilePaused, new PausedRebuilds());
        InitHookReplay.skipRebuiltWith(ResourceReload::isRebuiltWhilePaused);
        OffWindowObjects.setReloading(() -> ModViews.findLoadingOverlay() != null && isReloadMaking());
        InitHookReplay.answerWith(GameClasses::findLongLived, GameClasses::getStartupMethods,
                GameClasses::getJoinMethods);
        applier.runOnGameThreadWith(GameHold::onGameAndWait);
        CrashGuard.setMixinOwners(applier.getCarriedMixins()::findModShipping);
        OffModCommands.readUsesFrom(installed::getGraph);
        Linker.setMixinOwners(applier.getCarriedMixins()::findModShipping);
        TransformerChain chain = TransformerChain.bind(ForeignPatches.makeOwnerFinder(installed.getGraphJars()));
        BiFunction<Set<String>, Set<String>, Set<String>> foreign = findForeignPatches(instrumentation, installed,
                applier, chain);

        applier.rebuildAlsoWith((switched, on) -> {
            Set<String> also = new HashSet<>(foreign.apply(switched, on));
            also.addAll(ShutOverrides.findOwnersFor(switched));
            return also;
        });

        OffChecker offChecker = new OffChecker(GameRegistrations.getJournal(), () -> {
            ModThreadFinder threads = ModThreadFinder.ofLiveThreads(GameRegistrations::findModOrShipper,
                    classes::findLoadedNamed, Tripwire::isLinking);
            SwitchTimes.mark("off check: threads");
            return id -> {
                List<String> still = new ArrayList<>(threads.findRunning(id).stream()
                        .filter(thread -> !thread.isOwn()).map(ModThreadFinder.ModThread::describe).toList());

                still.addAll(RegistryEntries.describe(id));
                return still;
            };
        });
        ModPolicy.setSeenUses(new SeenUses(FabricLoader.getInstance().getConfigDir().resolve("hotwire/uses.json"),
                id -> installed.find(id).map(ModManifest::version).orElse(null)));
        ModSwitcher switcher = new ModSwitcher(installed.getGraph(), ModPolicy.makePlannerFor(installed.getGraph()),
                applier, progress, GameRegistrations.getJournal(),
                ModLifecycle.makeLifecycle(GameRegistrations.getJournal(), Minecraft.getInstance()));
        ModTripwire tripwire = new ModTripwire(instrumentation, applier.getRebuilder(), classes::findLoaded);
        applier.getRebuilder().setRider(tripwire.getRide());
        FieldReferences fieldReferences = new FieldReferences(instrumentation, applier.getRebuilder(),
                classes::findOriginalBytes,
                mod -> installed.getGraphJars().stream().filter(jar -> jar.manifest().id().equals(mod)).findFirst()
                        .map(jar -> jar.patches().patches()).orElse(Set.of()), mod -> findHandedTo(mod, installed),
                mixin::getGeneratedBy);
        InitHookReplay.writeStaticsWith(fieldReferences::writeStatic);
        FinalWrites.writeWith(fieldReferences::writeFinal);
        FieldSlots.readStaticsWhen(fieldReferences::isInitialized);
        switcher.reopenToTurnOnWith(mod -> fieldReferences.hasRemovedFrom(mod)
                || NetworkHandlers.hasLostConnection(mod));
        switcher.reopenToTurnOffWith(new ConnectionLambdas(instrumentation)::findMods);
        switcher.restartToTurnOffWith(new RegistryHolders(instrumentation)::findOwnedBy);
        Tripwire.setOwnLocks(new ModLocks(instrumentation, fieldReferences::isInitialized)::of);
        applier.checkHealthWith(GameHealth::findProblems, (rebuilt, rehashed) -> {
            InternedKeys.refresh(rebuilt);
            fieldReferences.rehash(rehashed);
            EventInvokers.rebuild(rebuilt, classes::findLoaded, fieldReferences::isInitialized);
        });
        SwitchRunner runner = new SwitchRunner(switcher, offChecker, reports, tripwire, fieldReferences,
                new AsyncLogging(applier.getRebuilder(), instrumentation),
                new GeneratedWrappers(applier.getRebuilder(),
                mixin::getGeneratedBy), new MixinPlugins(mixin::getConfigObjects,
                        mixin.getProcessor()), chain);
        AddSupport adds = new AddSupport(installed, applier, switcher, classes, reports, gameDir, folders, queue,
                icons, ids -> runner.turnOff(ids).result());
        UpdateSupport updates = new UpdateSupport(installed, applier, switcher, classes, folders, queue, reports,
                icons);
        updates.setAdder(adds.getAdder());
        return new HotwireEngine.JvmParts(runner, adds, updates, instrumentation, loadTimeMixer, classes);
    }

    private static BiFunction<Set<String>, Set<String>, Set<String>> findForeignPatches(
            Instrumentation instrumentation, InstalledMods installed, SwitchApplier applier,
            @Nullable TransformerChain chain) {
        if (chain == null) {
            return (switched, on) -> Set.of();
        }
        applier.getRebuilder().getHooks().setAfterMixin(chain::apply);
        ConstantPools pools = ConstantPools.open(instrumentation);
        if (pools == null) {
            return (switched, on) -> Set.of();
        }
        return new ForeignPatches(instrumentation, chain, pools, HotwireEngine.class.getClassLoader(),
                installed.getGraphJars())::findClassesFor;
    }

    static Path findHotwireJar() throws JvmAccessException {
        Optional<Path> origin = FabricLoader.getInstance().getModContainer(HotwireClient.ID)
                .flatMap(mod -> mod.getOrigin().getPaths().stream().findFirst());
        if (origin.isEmpty()) {
            throw new JvmAccessException(JvmAccessException.Reason.NOT_AN_AGENT_JAR, "Hotwire's own jar is not known",
                    null);
        }
        return origin.get();
    }

    private static Set<String> findHandedTo(String mod, InstalledMods installed) {
        Set<String> named = new HashSet<>();
        FabricLoader loader = FabricLoader.getInstance();
        loader.getModContainer(mod).ifPresent(container -> {
            container.getMetadata().getCustomValues().keySet().forEach(key -> named.add(getNamespace(key)));
            if (container.getMetadata() instanceof LoaderModMetadata metadata) {
                metadata.getEntrypointKeys().forEach(key -> named.add(getNamespace(key)));
            }
        });
        named.removeIf(id -> id.equals(mod) || !installed.isInstalled(id));
        return named;
    }

    public static Path findCacheFolder() {
        String local = System.getenv("LOCALAPPDATA");
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path home = Path.of(System.getProperty("user.home"));
        Path base = home.resolve(".cache");
        if (local != null) {
            base = Path.of(local);
        } else if (xdg != null) {
            base = Path.of(xdg);
        } else if (System.getProperty("os.name", "").startsWith("Mac")) {
            base = home.resolve("Library/Caches");
        }
        String game = LoadedMods.findVersion("minecraft", "unknown");
        return base.resolve("hotwire").resolve(game);
    }

    private static String getNamespace(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }

    static HotwireEngine start() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        InstalledMods installed = readInstalledMods(loader);
        Progress progress = new Progress();
        LoggedFaults.install();
        GameReports reports = new GameReports(installed::nameOf);
        UpdateFolders folders = UpdateFolders.ofMods(ModsFolder.find(loader));
        UpdateQueue queue = new UpdateQueue(folders.queueFile());
        HotwireSettings settings = new HotwireSettings(loader.getConfigDir().resolve("hotwire/settings.json"));
        RootScreen.pauseWith(settings::isPausing);
        ModPolicy.setNestedJars(settings.getNestedJars());

        ModIcons icons = new ModIcons(installed);
        icons.readAhead(installed.getGraph().getIds());
        HotwireEngine engine;
        try {
            HotwireEngine.JvmParts parts = bind(installed, progress, reports, folders, queue, gameDir, icons);
            engine = new HotwireEngine(installed, progress, reports, folders, queue, settings, icons,
                    new HotwireEngine.Bound.Ready(parts));
            HotwireEngine made = engine;
            parts.runner().getSwitcher().prepareLookWith(() -> parts.runner().prepareLookFor(made.findSwitchableOn()));
            new SwapAtClose(queue, folders, findHotwireJar()).arm();
            QuietThreads.install();
            LoadedClassBytes loadedBytes = parts.adds().getApplier().getRebuilder().getLoadedBytes();
            Fields.readWith(name -> parts.classes().findLoaded(name.replace('.', '/')) != null, type -> {
                String internal = type.getName().replace('.', '/');
                byte[] known = loadedBytes.findKnown(type);
                if (known == null) {
                    known = loadedBytes.findAsLoaded(internal);
                }
                return known != null ? known : parts.classes().findOriginalBytes(internal);
            });
            Fields.preloadStatics(parts.instrumentation());

            parts.runner().getSwitcher().getFiller().watchPlaying(CacheFillControl::isPlaying);
            parts.runner().getSwitcher().getFiller().fillSample();
        } catch (JvmAccessException | IllegalStateException e) {
            LOGGER.error("[Hotwire] Switching mods is not possible in this game: {}", e.getMessage(), e);
            engine = new HotwireEngine(installed, progress, reports, folders, queue, settings, icons,
                    new HotwireEngine.Bound.Refused(Lang.get("hotwire.switch.no_instrument", e.getMessage())));
        }
        engine.cleanUpdateFolders();
        HotwireEngine started = engine;
        OffCodeNotice.install(started, installed);
        BiConsumer<String, Boolean> say = (line, bad) -> {
            started.showMessage(line, bad);
            started.refreshScreen();
        };
        CrashGuard.install(installed, engine.getTasks(), () -> {
            HotwireEngine.JvmParts parts = started.findParts();
            return parts == null ? null : parts.runner();
        }, say, started::writeReport);
        OwnThrowNotice.install(engine.getTasks(), say,
                why -> started.writeReport(Lang.get("hotwire.report.file.hotwire_error"), why));
        HotwireEngine.JvmParts ready = started.findParts();
        if (ready != null) {
            ready.runner().setAfterOff(started.getDisabled()::disableAfter);
            ready.runner().setBeforeOn(started.getDisabled()::restore);
        }
        CrashReports.watch(engine.getTasks(), engine.getZips());
        return engine;
    }
}
