package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.AddSupport;
import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.fabric.rebuild.ForeignPatches;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.rebuild.GameMixinCopy;
import dev.moon.hotwire.fabric.rebuild.GeneratedClasses;
import dev.moon.hotwire.fabric.rebuild.KnotInternals;
import dev.moon.hotwire.fabric.rebuild.LocalRefs;
import dev.moon.hotwire.fabric.rebuild.MixinConfigs;
import dev.moon.hotwire.fabric.rebuild.MixinInternals;
import dev.moon.hotwire.fabric.rebuild.TransformerChain;
import dev.moon.hotwire.fabric.registration.AsyncLogging;
import dev.moon.hotwire.fabric.registration.ConnectionLambdas;
import dev.moon.hotwire.fabric.registration.EventInvokers;
import dev.moon.hotwire.fabric.registration.FieldReferences;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.registration.GeneratedWrappers;
import dev.moon.hotwire.fabric.registration.InternedKeys;
import dev.moon.hotwire.fabric.registration.MixinPlugins;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.fabric.registration.ModLocks;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.OffModCommands;
import dev.moon.hotwire.fabric.registration.RegistryEntries;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.jar.ModIndex;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FinalWrites;
import dev.moon.hotwire.redefine.InitHookReplay;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.OffWindowObjects;
import dev.moon.hotwire.redefine.ShutOverrides;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.ModThreadFinder;
import dev.moon.hotwire.switching.ModMixins;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.OffChecker;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.switching.SwitchTimes;
import dev.moon.hotwire.ui.Progress;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
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

    static InstalledMods readInstalledMods(FabricLoader loader) throws IOException {
        Path mods = ModsFolder.find(loader);
        ModIndex.Snapshot snapshot = new ModIndex(ForkJoinPool.commonPool()).scan(mods);
        List<ModJar> jars = new ArrayList<>();
        Map<String, Path> jarOf = new HashMap<>();
        Set<String> topLevel = new HashSet<>();
        for (ModIndex.Entry entry : snapshot.mods()) {
            jars.add(entry.jar());
            topLevel.add(entry.jar().manifest().id());
            jarOf.put(entry.jar().manifest().id(), entry.file());
        }
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
        return new InstalledMods(jars, jarOf, topLevel);
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
        KnotInternals knot = KnotInternals.bind();
        MixinInternals mixin = MixinInternals.bind();
        LocalRefs.prepare();
        Set<String> loadedIds = new HashSet<>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> loadedIds.add(mod.getMetadata().getId()));
        Map<String, ModMixins> mixins = MixinConfigs.withCodeAdded(
                MixinConfigs.of(installed.getGraphJars(), loadedIds), mixin.getConfigObjects());
        GameClasses classes = new GameClasses(knot, HotwireEngine.class.getClassLoader());
        SwitchApplier applier = new SwitchApplier(
                new ClassRebuilder(instrumentation, GameMixinCopy.create(knot, mixin, MixinConfigs.readDecorations(
                        mixins, mixin))),
                classes, mixins, mixins.keySet(), MixinConfigs.findUnowned(mixin.getConfigs(), mixins));
        applier.getRebuilder().resolveArgsWith(mixin::getArgsClassFor);
        applier.getRebuilder().getHooks()
                .sendGeneratedTo(new GeneratedClasses(gameDir.resolve("hotwire").resolve("generated")) ::write);
        applier.orderConfigsAs(mixin.getConfigs());
        ClassFileTransformer loadTimeMixer = applier.makeLoadTimeMixer();
        applier.holdRedefinitionWith(() -> GameHold.begin(Minecraft.getInstance())::release);
        applier.rebuildObjectsWith(ResourceReload::isRebuiltWhilePaused, new PausedRebuilds());
        InitHookReplay.skipRebuiltWith(ResourceReload::isRebuiltWhilePaused);
        OffWindowObjects.setReloading(() -> ModViews.findLoadingOverlay() != null && isReloadMaking());
        InitHookReplay.answerWith(GameClasses::findLongLived, GameClasses::getStartupMethods);
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
        ModSwitcher switcher = new ModSwitcher(installed.getGraph(), ModPolicy.makePlannerFor(installed.getGraph()),
                applier, progress, GameRegistrations.getJournal(),
                ModLifecycle.makeLifecycle(GameRegistrations.getJournal(), Minecraft.getInstance()));
        ModTripwire tripwire = new ModTripwire(instrumentation, applier.getRebuilder(), classes::findLoaded);
        FieldReferences fieldReferences = new FieldReferences(instrumentation, applier.getRebuilder(),
                classes::findOriginalBytes,
                mod -> installed.getGraphJars().stream().filter(jar -> jar.manifest().id().equals(mod)).findFirst()
                        .map(jar -> jar.patches().patches()).orElse(Set.of()), mod -> findHandedTo(mod, installed),
                mixin::getGeneratedBy);
        InitHookReplay.writeStaticsWith(fieldReferences::writeStatic);
        FinalWrites.writeWith(fieldReferences::writeFinal);
        switcher.reopenToTurnOnWith(mod -> fieldReferences.hasRemovedFrom(mod)
                || NetworkHandlers.hasLostConnection(mod));
        switcher.reopenToTurnOffWith(new ConnectionLambdas(instrumentation)::findMods);
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
        return new HotwireEngine.JvmParts(runner,
                new AddSupport(installed, applier, switcher, classes, reports, gameDir, folders, queue, icons,
                        ids -> runner.turnOff(ids).result()),
                new UpdateSupport(installed, applier, switcher, classes, folders, queue, reports, icons),
                instrumentation, loadTimeMixer, classes);
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

    private static String getNamespace(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }
}
