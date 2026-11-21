package dev.moon.hotwire.add;

import dev.moon.hotwire.jar.AccessWidenerText;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.WidenerUses;
import dev.moon.hotwire.redefine.Relocations;
import dev.moon.hotwire.update.UpdateDiffer;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JarUseReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(JarUseReader.class);

    private JarUseReader() {
    }

    public record JarClasses(Map<String, byte[]> classes, String texts) {
    }

    public static @Nullable JarClasses readJar(@Nullable Path path) {
        if (path == null) {
            return null;
        }
        try {
            Map<String, byte[]> classes = new LinkedHashMap<>();
            StringBuilder texts = new StringBuilder();

            Map<String, byte[]> read = UpdateDiffer.readEntries(path,
                    entry -> entry.endsWith(".class") || entry.endsWith(".json"));
            read.forEach((entry, bytes) -> {
                if (entry.endsWith(".class")) {
                    classes.put(entry.substring(0, entry.length() - ".class".length()), bytes);
                } else if (entry.endsWith(".json")) {
                    texts.append(new String(bytes, StandardCharsets.UTF_8)).append('\n');
                }
            });
            return new JarClasses(classes, texts.toString());
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] {} could not be read for its widener's uses", path, e);
            return null;
        }
    }

    public static List<WidenerUses.Use> findUsesOf(@Nullable JarClasses jar, List<AccessWidenerText.Clause> loaded,
            WidenerUses.GameAccess game) {
        if (jar == null) {
            return List.of();
        }
        Supplier<List<ClassNode>> nodes = new Supplier<>() {
            private @Nullable List<ClassNode> read;

            @Override
            public List<ClassNode> get() {
                if (this.read == null) {
                    this.read = WidenerUses.readNodes(jar.classes());
                }
                return this.read;
            }
        };
        List<WidenerUses.Use> uses = new ArrayList<>(WidenerUses.scan(WidenerUses.findOpenings(loaded, game),
                game, nodes));
        uses.addAll(WidenerUses.shutUses(loaded, game, nodes));
        return uses;
    }

    public static boolean isMovable(WidenerUses.Use use, @Nullable JarClasses jar, WidenerUses.GameAccess game,
            boolean logged) {
        boolean packagePrivateParent = switch (use.kind()) {
            case SUBCLASS -> true;
            case PARENT_CONSTRUCTOR -> game.findModifiers(use.owner(), use.name(), use.desc())
                    .stream().noneMatch(Modifier::isPrivate);
            default -> false;
        };
        if (jar == null || !packagePrivateParent || (game.getModifiers(use.owner()) & Opcodes.ACC_FINAL) != 0) {
            return false;
        }
        String blocker = Relocations.findBlocker(use.inClass(), jar.classes(), jar.texts());
        if (blocker != null && logged) {
            LOGGER.info("[Hotwire] {} cannot move beside {}: {}", use.inClass(), use.owner(), blocker);
        }
        return blocker == null;
    }
}
