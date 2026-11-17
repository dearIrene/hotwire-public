package dev.moon.hotwire.engine;

import dev.moon.hotwire.dev.DevMixin;
import dev.moon.hotwire.dev.DevSession;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.CheckedResult;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.PlayerMessages;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.ModUpdater;
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

public final class DevEngine implements DevSession.Engine {
    private final Supplier<@Nullable JvmParts> parts;
    private final Supplier<String> refusal;
    private final InstalledMods installed;

    public DevEngine(Supplier<@Nullable JvmParts> parts, Supplier<String> refusal, InstalledMods installed) {
        this.parts = parts;
        this.refusal = refusal;
        this.installed = installed;
    }

    @Override
    public UpdatePreview preview(Path jar) {
        JvmParts found = this.parts.get();
        return found == null ? new UpdatePreview.Refused(String.valueOf(jar.getFileName()), this.refusal.get())
                : found.updates().getUpdater().preview(jar);
    }

    @Override
    public ModUpdater.Result updateInPlace(Path jar) {
        JvmParts found = this.parts.get();
        return found == null ? new ModUpdater.Result.Refused(this.refusal.get())
                : found.updates().update(jar, UpdateMethod.IN_PLACE);
    }

    @Override
    public DevSession.Restart restart(String id) {
        JvmParts found = this.parts.get();
        if (found == null) {
            return new DevSession.Restart(this.refusal.get(), List.of());
        }
        CheckedResult checked = SwitchRunner.runQuietly(() -> found.runner().turnOff(List.of(id)));
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
        JvmParts found = this.parts.get();
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
    public boolean isInitialized(Class<?> type) {
        JvmParts found = this.parts.get();
        return found != null && found.runner().isInitialized(type);
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
        JvmParts found = this.parts.get();
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
}
