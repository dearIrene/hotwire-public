package dev.moon.hotwire.fabric;

import dev.moon.hotwire.add.AddSupport;
import dev.moon.hotwire.cache.CacheBudget;
import dev.moon.hotwire.cache.CacheStore;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.dev.DevSession;
import dev.moon.hotwire.engine.CacheLimit;
import dev.moon.hotwire.engine.CrashGuard;
import dev.moon.hotwire.engine.DevSetups;
import dev.moon.hotwire.engine.EarlyModsReader;
import dev.moon.hotwire.engine.HotwireEngine;
import dev.moon.hotwire.engine.InstalledMods;
import dev.moon.hotwire.engine.JvmParts;
import dev.moon.hotwire.engine.LifecycleListeners;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.engine.ModTripwire;
import dev.moon.hotwire.engine.ModsFolder;
import dev.moon.hotwire.engine.OwnThrowNotice;
import dev.moon.hotwire.engine.RestartOnlyFile;
import dev.moon.hotwire.engine.SeenUses;
import dev.moon.hotwire.engine.SwitchRunner;
import dev.moon.hotwire.fabric.add.AddGameSide;
import dev.moon.hotwire.fabric.add.HiddenPacks;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.rebuild.GameMixinCopy;
import dev.moon.hotwire.fabric.rebuild.LivePluginShapes;
import dev.moon.hotwire.fabric.rebuild.MixinConfigs;
import dev.moon.hotwire.fabric.rebuild.MixinInternals;
import dev.moon.hotwire.fabric.registration.ConnectionLambdas;
import dev.moon.hotwire.fabric.registration.EventInvokers;
import dev.moon.hotwire.fabric.registration.InternedKeys;
import dev.moon.hotwire.fabric.registration.LiveConnections;
import dev.moon.hotwire.fabric.registration.MixinPlugins;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.registration.RegistryEntries;
import dev.moon.hotwire.fabric.registration.RegistryHolders;
import dev.moon.hotwire.fabric.report.MachineDetails;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.screen.RootScreen;
import dev.moon.hotwire.fabric.update.UpdateGameSide;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.jvm.JarChannels;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.jvm.LoadedClassList;
import dev.moon.hotwire.rebuild.ConstantPools;
import dev.moon.hotwire.rebuild.ForeignPatches;
import dev.moon.hotwire.rebuild.GeneratedClasses;
import dev.moon.hotwire.rebuild.KnotInternals;
import dev.moon.hotwire.rebuild.LocalRefs;
import dev.moon.hotwire.rebuild.TransformerChain;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.FinalWrites;
import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.InitHookReplay;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.ReplacedStatics;
import dev.moon.hotwire.redefine.ShutOverrides;
import dev.moon.hotwire.redefine.SiteCache;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.redefine.TripwireSites;
import dev.moon.hotwire.registration.AsyncLogging;
import dev.moon.hotwire.registration.ElementLook;
import dev.moon.hotwire.registration.FieldReferences;
import dev.moon.hotwire.registration.Fields;
import dev.moon.hotwire.registration.GameLibraries;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.GeneratedWrappers;
import dev.moon.hotwire.registration.ModLocks;
import dev.moon.hotwire.registration.ModThreadFinder;
import dev.moon.hotwire.registration.OneConfigSaves;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.QuietThreads;
import dev.moon.hotwire.registration.StaticCopies;
import dev.moon.hotwire.report.GameReports;
import dev.moon.hotwire.report.HotwireLog;
import dev.moon.hotwire.report.LoggedFaults;
import dev.moon.hotwire.report.StartTimes;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.switching.BestTimes;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.Progress;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchHistory;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.notices.Notice;
import dev.moon.hotwire.ui.notices.Notices;
import dev.moon.hotwire.update.LiveNames;
import dev.moon.hotwire.update.OldStatics;
import dev.moon.hotwire.update.SwapAtClose;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import dev.moon.hotwire.update.UpdateSupport;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EngineStart {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineStart.class);

    private EngineStart() {
    }

    static JvmParts bind(InstalledMods installed, Progress progress, GameReports reports,
            UpdateFolders folders, UpdateQueue queue, Path gameDir, ModIcons icons) throws JvmAccessException {
        StartTimes.Laps laps = StartTimes.startLaps("engine bind: ");
        Instrumentation instrumentation = JvmAccess.getInstrumentation(findHotwireJarIfKnown(),
                gameDir.resolve("hotwire").resolve("agent"));
        JarChannels.open(instrumentation);
        LOGGER.info("[Hotwire] {} open jars read on through an interrupt", JarChannels.makeUninterruptible());
        laps.endLap("reach the JVM, open jars");
        KnotInternals knot = KnotInternals.bind();
        MixinInternals mixin = MixinInternals.bind();
        LocalRefs.prepare();
        laps.endLap("reach Knot and Mixin");
        Set<String> loadedIds = new HashSet<>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> loadedIds.add(mod.getMetadata().getId()));
        Map<String, ModMixins> mixins = MixinConfigs.withCodeAdded(
                ModMixins.readAll(installed.getGraphJars(), loadedIds), mixin.getConfigObjects());

        SiteCache.finish();
        laps.endLap("wait for the call sites' notes");
        if (HandlerGuards.ENABLED) {
            Set<String> fixed = new HashSet<>();
            mixins.keySet().stream().filter(id -> !ModPolicy.isSwitchable(id)).forEach(fixed::add);
            fixed.add("hotwire");
            Set<String> fixedMixins = new HashSet<>();
            mixins.forEach((id, mod) -> {
                if (fixed.contains(id)) {
                    mod.owns().forEach(name -> fixedMixins.add(name.replace('/', '.')));
                }
            });
            HandlerGuards.leaveHandlersOf(fixed, fixedMixins);
            mixins.forEach((id, mod) -> {
                if (ModPolicy.isSwitchable(id)) {
                    TripwireSites.registerClasses(id, mod.owns().stream()
                            .filter(name -> !GameLibraries.has(name, null)).collect(Collectors.toSet()));
                }
            });
        }
        laps.endLap("read the mixin configs, call sites");
        GameClasses classes = new GameClasses(knot, HotwireEngine.class.getClassLoader());
        HandlerGuards.readOriginalsWith(classes::findOriginalBytes);
        SwitchApplier applier = new SwitchApplier(
                new ClassRebuilder(instrumentation, GameMixinCopy.create(mixin, MixinConfigs.readDecorations(
                        mixins, mixin), classes::findOriginalBytes), mixin::getArgsClassFor),
                classes, mixins, mixins.keySet(), MixinConfigs.findUnowned(mixin.getConfigs(), mixins),
                mixin.getConfigs());
        HandlerGuards.readOverwritersWith(applier.getCarriedMixins()::findOverwriters);
        laps.endLap("applier and Mixin copy");
        long limit = (long) CacheLimit.read(CacheLimit.findCacheFolder()) << 20;
        applier.getRebuilder().getCache().cacheIn(
                CacheStore.open(CacheLimit.findCacheFolder().resolve("mixed"), "mixed",
                        CacheBudget.findOwnCap(CacheLimit.findCacheFolder(), limit)), LivePluginShapes::ask);
        CacheLimit.fitLater(CacheLimit.findCacheFolder(), limit);
        laps.endLap("open the switch cache");
        applier.getRebuilder().getHooks()
                .sendGeneratedTo(new GeneratedClasses(gameDir.resolve("hotwire").resolve("generated")) ::write);
        laps.endLap("clear the generated classes");
        ClassFileTransformer loadTimeMixer = applier.makeLoadTimeMixer();
        TransformerChain.setMixedAsItLoads(name -> applier.isMixedAsItLoads(name.replace('.', '/')));
        InitHookReplay.nameSucceededWith(LiveNames::findOwnKeys);
        OffModCommands.readUsesFrom(installed::getGraph);
        HiddenPacks.readContainersWith(id -> installed.getGraph().getContainers(id));
        Linker.setMixinOwners(applier.getCarriedMixins()::findModShipping);
        laps.endLap("load-time mixer, hooks");
        TransformerChain chain = TransformerChain.bind(ForeignPatches.makeOwnerFinder(installed.getGraphJars()));
        BiFunction<Set<String>, Set<String>, Set<String>> foreign = findForeignPatches(instrumentation, installed,
                applier, chain);

        applier.rebuildAlsoWith((switched, on) -> {
            Set<String> also = new HashSet<>(foreign.apply(switched, on));
            also.addAll(ShutOverrides.findOwnersFor(switched));
            return also;
        });

        laps.endLap("foreign patches");
        OffChecker offChecker = new OffChecker(GameRegistrations.getJournal(), () -> {
            ModThreadFinder threads = ModThreadFinder.ofLiveThreads(Owners::findModOrShipper,
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
        ModPolicy.setStaying(applier::patchesLiveBackend);
        laps.endLap("off checker, seen uses");
        ModTripwire tripwire = new ModTripwire(instrumentation, applier.getRebuilder(), classes::findLoaded);
        applier.addRider(tripwire.getRide());
        laps.endLap("tripwire");
        FieldReferences fieldReferences = new FieldReferences(instrumentation, applier.getRebuilder(),
                classes::findOriginalBytes, classes::readOriginalBytes,
                mod -> installed.getGraphJars().stream().filter(jar -> jar.manifest().id().equals(mod)).findFirst()
                        .map(jar -> jar.patches().patches()).orElse(Set.of()), mod -> findHandedTo(mod, installed),
                mixin::getGeneratedBy);
        InitHookReplay.writeStaticsWith(fieldReferences::writeStatic);
        ReplacedStatics.swapWith(replaced -> {
            LiveConnections.swap(replaced);
            StaticCopies.swap(replaced);
        });
        FinalWrites.writeWith(fieldReferences::writeFinal);
        FieldSlots.readStaticsWhen(fieldReferences::isInitialized);
        laps.endLap("field references, statics");
        RestartOnlyFile restartOnly = new RestartOnlyFile(FabricLoader.getInstance().getConfigDir()
                .resolve("hotwire/restart-only.json"), id -> installed.find(id).map(ModManifest::version).orElse(null));
        ModSwitcher switcher = new ModSwitcher(installed.getGraph(), ModPolicy.makePlannerFor(installed.getGraph()),
                applier, progress, GameRegistrations.getJournal(),
                ModLifecycle.makeLifecycle(GameRegistrations.getJournal(), Minecraft.getInstance()),
                new ModSwitcher.Checks(mod -> fieldReferences.hasRemovedFrom(mod)
                        || NetworkHandlers.hasLostConnection(mod), new ConnectionLambdas(instrumentation)::findMods,
                        new RegistryHolders(instrumentation)::findOwnedBy, restartOnly.findCurrent(),
                        restartOnly::add));
        LifecycleListeners.setIsOn(switcher::isOn);

        LifecycleListeners.add(new OneConfigSaves(LifecycleListeners::isListening));
        laps.endLap("switcher");
        Tripwire.setOwnLocks(new ModLocks(instrumentation, fieldReferences::isInitialized)::of);
        applier.putRightAfterRebuildWith((rebuilt, rehashed) -> {
            InternedKeys.refresh(rebuilt);
            fieldReferences.rehash(rehashed);
            EventInvokers.rebuild(rebuilt, classes::findLoaded, fieldReferences::isInitialized);
        });
        laps.endLap("reopen checks, locks, health");
        SwitchRunner runner = new SwitchRunner(switcher, offChecker, reports, tripwire, fieldReferences,
                new AsyncLogging(applier.getRebuilder(), instrumentation),
                new GeneratedWrappers(applier.getRebuilder(),
                mixin::getGeneratedBy), new GameSwitchUnits(new MixinPlugins(mixin::getConfigObjects,
                        mixin.getProcessor())), installed, chain);
        AddSupport adds = new AddSupport(installed, applier, switcher, classes, reports, gameDir, folders, queue,
                icons, new AddGameSide(), ids -> runner.turnOff(ids).result(), runner::lookAheadForAll);
        UpdateSupport updates = new UpdateSupport(installed, applier, switcher, classes, folders, queue, reports,
                icons, adds.getAdder(), ids -> SwitchRunner.runQuietly(() -> runner.turnOff(ids)).result(),
                ids -> SwitchRunner.runQuietly(() -> runner.turnOn(ids)).result(),
                leaving -> OldStatics.takeOut(leaving, LoadedClassList.of(instrumentation),
                        fieldReferences::isInitialized, classes::findOriginalBytes), new UpdateGameSide());
        adds.getAdder().setCarriedLibraries(updates);
        laps.endLap("runner, adds, updates");
        return new JvmParts(runner, adds, updates, instrumentation, loadTimeMixer, classes);
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

    private static void describeTrace(Throwable thrown, StringBuilder out, String prefix) {
        out.append(prefix).append(thrown).append('\n');
        for (StackTraceElement frame : thrown.getStackTrace()) {
            out.append("\tat ").append(frame).append('\n');
        }
        for (Throwable suppressed : thrown.getSuppressed()) {
            describeTrace(suppressed, out, "Suppressed: ");
        }
        Throwable cause = thrown.getCause();
        if (cause != null) {
            describeTrace(cause, out, "Caused by: ");
        }
    }

    private static void forgetStartFailure(Path gameDir) {
        try {
            Files.deleteIfExists(gameDir.resolve("hotwire").resolve("logs").resolve(HotwireLog.START_FAILURE));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[Hotwire] the last start's failure could not be deleted: {}", e.toString());
        }
    }

    static void writeStartFailure(Path gameDir, Throwable failure) {
        StringBuilder trace = new StringBuilder();
        describeTrace(failure, trace, "");
        try {
            Path logs = gameDir.resolve("hotwire").resolve("logs");
            Files.createDirectories(logs);
            Files.writeString(logs.resolve(HotwireLog.START_FAILURE), trace.toString());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] the start's failure could not be written down", e);
        }
    }

    private static boolean isLunar() {
        try {
            Class.forName("com.moonsworth.lunar.genesis.Genesis", false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static @Nullable Path findSwapperJar(UpdateFolders folders) {
        Path known = findHotwireJarIfKnown();
        if (known != null && Files.isRegularFile(known)) {
            return known;
        }
        try (Stream<Path> jars = Files.list(folders.mods())) {
            return jars.filter(jar -> jar.getFileName().toString().startsWith("hotwire-")
                    && jar.getFileName().toString().endsWith(".jar") && SwapAtClose.canRunFrom(jar)).findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable Path findHotwireJarIfKnown() {
        try {
            Path jar = findHotwireJar();
            return jar.getFileSystem().equals(FileSystems.getDefault()) ? jar : null;
        } catch (JvmAccessException | RuntimeException e) {
            return null;
        }
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

    private static String getNamespace(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }

    static HotwireEngine start() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        StartTimes.Laps laps = StartTimes.startLaps("engine start: ");
        InstalledMods installed = EarlyModsReader.take(loader);
        laps.endLap("read the installed mods");
        Progress progress = new Progress();
        LoggedFaults.install();
        GameReports reports = new GameReports(installed::nameOf, new MachineDetails());
        UpdateFolders folders = UpdateFolders.ofMods(ModsFolder.find(loader));
        UpdateQueue queue = new UpdateQueue(folders.queueFile());
        HotwireSettings settings = new HotwireSettings(loader.getConfigDir().resolve("hotwire/settings.json"));
        SwitchHistory.open(loader.getConfigDir().resolve("hotwire/history.json"));
        BestTimes.open(loader.getConfigDir().resolve("hotwire/records.txt"));
        BestTimes.listen(beaten -> {
            List<String> asked = beaten.asked().isEmpty() ? beaten.ids() : beaten.asked();
            String who = installed.nameOf(asked.getFirst());
            int others = beaten.ids().size() - 1;
            Notices.show(Notice.ofRecord(Lang.get("hotwire.record.title"), (others == 0 ? who
                    : Lang.get("hotwire.record.with", who, others)) + ": " + Lang.get(beaten.off()
                    ? "hotwire.record.off" : "hotwire.record.on", BestTimes.format(beaten.millis())) + " "
                    + Lang.get("hotwire.record.was", BestTimes.format(beaten.before()))));
        });
        RootScreen.pauseWith(settings::isPausing);
        ModPolicy.setNestedJars(settings.getNestedJars());

        ModIcons icons = new ModIcons(installed);
        icons.readAhead(installed.getGraph().getIds());
        laps.endLap("settings, folders, icons");
        HotwireEngine engine;
        try {
            JvmParts parts = bind(installed, progress, reports, folders, queue, gameDir, icons);
            laps.endLap("bind, in all");
            engine = new HotwireEngine(installed, progress, reports, folders, queue, settings, icons,
                    new HotwireEngine.Bound.Ready(parts), new EngineGameSide());
            laps.endLap("make the engine");
            HotwireEngine made = engine;
            ElementLook.useMods(made::findSwitchable);
            parts.runner().getSwitcher().prepareLookWith(() -> parts.runner().prepareLook());
            new SwapAtClose(queue, folders, findHotwireJar()).arm();
            QuietThreads.install();
            laps.endLap("look, swap at close, quiet threads");
            LoadedClassBytes loadedBytes = parts.adds().getApplier().getRebuilder().getLoadedBytes();
            Fields.readWith(name -> parts.classes().findLoaded(name.replace('.', '/')) != null, type -> {
                if (type.isHidden()) {
                    return null;
                }
                String internal = Type.getInternalName(type);
                byte[] known = loadedBytes.findKnown(type);
                if (known == null) {
                    known = loadedBytes.findAsLoaded(internal);
                }
                return known != null ? known : ((GameClasses) parts.classes()).readOriginalBytes(internal);
            });
            Fields.preloadStatics(parts.instrumentation());
            laps.endLap("fields");

            parts.runner().getSwitcher().getFiller().watchPlaying(Games.getNow()::isPlaying);
            if (settings.isAutoFillOn()) {
                parts.runner().getSwitcher().getFiller().fillAll();
            } else {
                parts.runner().getSwitcher().getFiller().fillChanged();
            }
            laps.endLap("switch cache");
            forgetStartFailure(gameDir);
        } catch (JvmAccessException | RuntimeException | LinkageError e) {
            LOGGER.error("[Hotwire] Switching mods is not possible in this game: {}", e.getMessage(), e);
            writeStartFailure(gameDir, e);
            engine = new HotwireEngine(installed, progress, reports, folders, queue, settings, icons,
                    new HotwireEngine.Bound.Refused(Lang.get(isLunar() ? "hotwire.switch.lunar"
                            : "hotwire.switch.no_instrument")), new EngineGameSide());

            Path hotwire = findSwapperJar(folders);
            if (hotwire != null) {
                new SwapAtClose(queue, folders, hotwire).arm();
            }
        }
        engine.cleanUpdateFolders();
        laps.endLap("clean the update folders");
        HotwireEngine started = engine;
        OffCodeNotice.install(started, installed);
        BiConsumer<String, Boolean> say = (line, bad) -> {
            started.showMessage(line, bad);
            started.refreshScreen();
        };
        CrashGuard.install(installed, engine.getTasks(), () -> {
            JvmParts parts = started.findParts();
            return parts == null ? null : parts.runner();
        }, say, started::writeReport, () -> started.getSettings().isAntiCrashOn(),
                id -> DevSession.isDevMod(id) && started.getSettings().isDevCatchOn(DevSetups.get().inSetup()));
        OwnThrowNotice.install(engine.getTasks(), say,
                why -> started.writeReport(Lang.get("hotwire.report.file.hotwire_error"), why));
        JvmParts ready = started.findParts();
        if (ready != null) {
            ready.runner().setAfterOff(result -> {
                started.getDisabled().disableAfter(result);

                Games.getNow().runOnGame(started::checkQueue);
            });
            ready.runner().setBeforeOn(started.getDisabled()::restore);
        }
        CrashReports.watch(engine.getTasks(), engine.getZips());
        laps.endLap("crash guards and notices");
        return engine;
    }
}
