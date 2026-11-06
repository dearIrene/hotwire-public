package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.fabric.dev.DevSession;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.fabric.update.UpdateSupport;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class DevEngine implements DevSession.Engine {
    private final Supplier<HotwireEngine.@Nullable JvmParts> parts;
    private final Supplier<String> refusal;
    private final InstalledMods installed;
    private final HotwireSettings settings;

    DevEngine(Supplier<HotwireEngine.@Nullable JvmParts> parts, Supplier<String> refusal, InstalledMods installed,
            HotwireSettings settings) {
        this.parts = parts;
        this.refusal = refusal;
        this.installed = installed;
        this.settings = settings;
    }

    @Override
    public UpdatePreview preview(Path jar) {
        HotwireEngine.JvmParts found = this.parts.get();
        return found == null ? new UpdatePreview.Refused(String.valueOf(jar.getFileName()), this.refusal.get())
                : found.updates().preview(jar);
    }

    @Override
    public ModUpdater.Result updateInPlace(Path jar) {
        HotwireEngine.JvmParts found = this.parts.get();
        return found == null ? new ModUpdater.Result.Refused(this.refusal.get())
                : found.updates().update(jar, UpdateMethod.IN_PLACE);
    }

    @Override
    public String describe(ModUpdater.Result result) {
        return UpdateSupport.describe(result);
    }

    @Override
    public DevSession.Restart restart(String id) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return new DevSession.Restart(this.refusal.get(), List.of());
        }
        SwitchRunner.CheckedResult checked = SwitchRunner.runQuietly(() -> found.runner().turnOff(List.of(id)));
        if (!(checked.result() instanceof ModSwitcher.Result.Done done)) {
            return new DevSession.Restart(PlayerMessages.describe(checked.result(), this.installed::nameOf), List.of());
        }
        List<String> leftOver = checked.check() instanceof Report.OffCheck.PartlyOff partly
                ? partly.stillLive().getOrDefault(id, List.of()) : List.of();
        ModSwitcher.Result on = SwitchRunner.runQuietly(() -> found.runner().turnOn(done.plan().order())).result();
        return new DevSession.Restart(PlayerMessages.isBad(on) ? PlayerMessages.describe(on, this.installed::nameOf)
                : null, leftOver);
    }

    @Override
    public List<Class<?>> findLoaded(Set<String> names) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return List.of();
        }

        List<Class<?>> loaded = new ArrayList<>();
        for (String name : names) {
            Class<?> type = found.classes().findLoaded(name.replace('.', '/'));
            if (type != null) {
                loaded.add(type);
            }
        }
        return loaded;
    }

    @Override
    public List<DevMixin> findMixins(String id) {
        List<ModJar> jars = this.installed.getGraphJars();
        ModJar own = jars.stream().filter(jar -> jar.manifest().id().equals(id)).findFirst().orElse(null);
        if (own == null) {
            return List.of();
        }
        List<DevMixin> found = new ArrayList<>();
        for (String target : new TreeSet<>(own.patches().patches())) {
            List<String> others = new ArrayList<>();
            for (ModJar jar : jars) {
                if (!jar.manifest().id().equals(id) && jar.patches().patches().contains(target)) {
                    others.add(jar.manifest().name());
                }
            }
            found.add(new DevMixin(target.substring(target.lastIndexOf('/') + 1), others));
        }
        return found;
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public List<String> redefineElsewhere(Map<String, byte[]> classes) {
        HotwireEngine.JvmParts found = this.parts.get();
        if (found == null) {
            return List.of();
        }

        ClassLoader modLoader = DevEngine.class.getClassLoader();
        List<ClassDefinition> definitions = new ArrayList<>();
        Map<String, byte[]> unknown = new HashMap<>();
        classes.forEach((entry, bytes) -> {
            String internal = entry.substring(0, entry.length() - ".class".length());
            Class<?> type = found.classes().findLoaded(internal);
            if (type == null) {
                unknown.put(internal.replace('/', '.'), bytes);
            } else if (type.getClassLoader() != modLoader && type.getClassLoader() != null) {
                definitions.add(new ClassDefinition(type, bytes));
            }
        });

        if (!unknown.isEmpty()) {
            for (Class<?> type : found.instrumentation().getAllLoadedClasses()) {
                byte[] bytes = unknown.get(type.getName());
                if (bytes != null && type.getClassLoader() != modLoader && type.getClassLoader() != null) {
                    definitions.add(new ClassDefinition(type, bytes));
                }
            }
        }
        if (definitions.isEmpty()) {
            return List.of();
        }
        try {
            found.instrumentation().redefineClasses(definitions.toArray(ClassDefinition[]::new));
            return List.of();
        } catch (ClassNotFoundException | UnmodifiableClassException | UnsupportedOperationException
                | LinkageError e) {
            return definitions.stream().map(definition -> definition.getDefinitionClass().getSimpleName()).toList();
        }
    }

    @Override
    public boolean isEveryReloadShown() {
        return this.settings.isEveryReloadShown();
    }

    @Override
    public int getKeptBuilds() {
        return this.settings.getKeptBuilds();
    }
}
