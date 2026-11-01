package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.jar.AccessWidenerText;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.WidenerUses;
import dev.moon.hotwire.redefine.AccessBridge;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.Relocations;
import dev.moon.hotwire.redefine.ShutOverrides;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AddWideners {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddWideners.class);
    private final ModAdder owner;

    AddWideners(ModAdder owner) {
        this.owner = owner;
    }

    Optional<String> findWidenerRefusal(ModJar part, @Nullable Path path) {
        Optional<String> agent = AddSupport.findAgentRefusal(part.manifest().name(), path);
        if (agent.isPresent()) {
            return agent;
        }
        if (part.accessWidener().isEmpty()) {
            return Optional.empty();
        }
        AccessWidenerText widener = part.accessWidener().get();
        String name = part.manifest().name();
        try {
            LoaderClassTweaker.check(widener, name);
        } catch (IllegalArgumentException e) {
            return Optional.of(Objects.requireNonNull(e.getMessage()));
        }
        List<AccessWidenerText.Clause> loaded = widener.getClausesOnLoadedClasses(this.owner.engine::isLoaded);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        List<AccessWidenerText.Clause> unbridgeable = WidenerUses.findUnbridgeable(loaded, this.owner.engine)
                .stream().filter(clause -> path == null || !WidenerUses.JUDGED_BY_USE.contains(clause.kind())).toList();
        if (!unbridgeable.isEmpty()) {
            return Optional.of(AccessWidenerText.describeRefusal(name, unbridgeable.get(0)));
        }
        JarUseReader.JarClasses jar = JarUseReader.readJar(path);
        for (WidenerUses.Use use : JarUseReader.findUsesOf(jar, loaded, this.owner.engine)) {
            if (!use.isFixable() && !JarUseReader.isMovable(use, jar, this.owner.engine, false)) {
                return Optional.of(name + " cannot be added: " + use.describe() + ", and the game has loaded that"
                        + " class already, so its access cannot change.");
            }
        }
        return Optional.empty();
    }

    Optional<String> findRefusalOfUpdate(AccessWidenerText widener, String name, Path path) {
        try {
            LoaderClassTweaker.check(widener, name);
        } catch (IllegalArgumentException e) {
            return Optional.of(Objects.requireNonNull(e.getMessage()));
        }
        List<AccessWidenerText.Clause> loaded = widener.getClausesOnLoadedClasses(this.owner.engine::isLoaded);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        List<AccessWidenerText.Clause> unbridgeable = WidenerUses.findUnbridgeable(loaded, this.owner.engine)
                .stream().filter(clause -> !WidenerUses.JUDGED_BY_USE.contains(clause.kind())).toList();
        if (!unbridgeable.isEmpty()) {
            return Optional.of(AccessWidenerText.describeRefusal(name, unbridgeable.get(0)));
        }
        JarUseReader.JarClasses jar = JarUseReader.readJar(path);
        for (WidenerUses.Use use : JarUseReader.findUsesOf(jar, loaded, this.owner.engine)) {
            if (!use.isFixable()) {
                return Optional.of(name + " cannot be updated while it runs: " + use.describe() + ", and the game"
                        + " has loaded that class already, so its access cannot change.");
            }
        }
        return Optional.empty();
    }

    void openOfUpdate(AccessWidenerText widener, ModJar part) {
        LoaderClassTweaker.apply(widener, part.manifest().name(), this.owner.engine::isLoaded);
        List<AccessWidenerText.Clause> loaded = widener.getClausesOnLoadedClasses(this.owner.engine::isLoaded);
        WidenerUses.Openings openings = WidenerUses.findOpenings(loaded, this.owner.engine);
        if (!openings.isEmpty()) {
            Linker.open(openings);
            for (String opened : openings.classes()) {
                Class<?> type = this.owner.engine.findLoaded(opened);
                if (type != null) {
                    AccessBridge.open(type);
                }
            }
            LOGGER.info("[Hotwire] {}'s update opens {} loaded classes and {} members through the bridge",
                    part.manifest().id(), openings.classes().size(), openings.members().size());
        }
    }

    void openLoaded(ModJar part, @Nullable Path path) {
        part.accessWidener().ifPresent(widener -> {
            List<AccessWidenerText.Clause> loaded = widener.getClausesOnLoadedClasses(this.owner.engine::isLoaded);

            JarUseReader.JarClasses jar = JarUseReader.readJar(path);

            Map<String, String> moves = new LinkedHashMap<>();
            Map<String, String> adopted = new LinkedHashMap<>();
            for (WidenerUses.Use use : JarUseReader.findUsesOf(jar, loaded, this.owner.engine)) {
                if (jar != null && !moves.containsKey(use.inClass())
                        && JarUseReader.isMovable(use, jar, this.owner.engine, true)) {
                    String target = Relocations.findTarget(use.inClass(), use.owner());
                    moves.put(use.inClass(), target);
                    Relocations.register(use.inClass(), target, jar.classes());
                    LOGGER.info("[Hotwire] {} moves to {}, beside {}", use.inClass(), target, use.owner());
                }
                if (use.kind() == WidenerUses.Kind.OVERRIDE) {
                    ShutOverrides.register(part.manifest().id(), use.owner(), use.name() + use.desc(), use.inClass());
                }
                if (use.kind() == WidenerUses.Kind.PARENT_CONSTRUCTOR) {
                    adopted.put(use.inClass(), use.owner());
                }
            }

            adopted.forEach((inClass, owner) -> {
                String loadsAs = moves.getOrDefault(inClass, inClass);
                Linker.adopt(loadsAs);
                LOGGER.info("[Hotwire] {} is made by adoption: {}'s constructor stays closed", loadsAs, owner);
            });
            if (!moves.isEmpty()) {
                Map<String, byte[]> generated = new LinkedHashMap<>();
                moves.forEach((moved, target) -> generated.put(target, Relocations.remap(
                        Objects.requireNonNull(Objects.requireNonNull(jar).classes().get(moved)))));
                this.owner.engine.getApplier().getRebuilder().getHooks().onGenerated(generated);
            }
            WidenerUses.Openings openings = WidenerUses.findOpenings(loaded, this.owner.engine);
            if (!openings.isEmpty()) {
                Linker.open(openings);
                for (String opened : openings.classes()) {
                    Class<?> type = this.owner.engine.findLoaded(opened);
                    if (type != null) {
                        AccessBridge.open(type);
                    }
                }
                LOGGER.info("[Hotwire] {} opens {} loaded classes and {} members for its own code through the bridge",
                        part.manifest().id(), openings.classes().size(), openings.members().size());
            }
        });
    }
}
