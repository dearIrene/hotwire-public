package dev.moon.hotwire.switching;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.DuckBridge;
import dev.moon.hotwire.redefine.Linker;
import dev.moon.hotwire.redefine.RebuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DuckRebridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuckRebridge.class);

    private DuckRebridge() {
    }

    static Set<String> findCalledAcross(Set<String> movedNow, Set<String> movedBefore) {
        Set<String> fresh = new HashSet<>(movedNow);
        fresh.removeAll(movedBefore);
        fresh.removeIf(method -> method.substring(0, method.indexOf('(')).contains("$"));
        return fresh;
    }

    @SuppressWarnings("ReferenceEquality")
    static void run(Set<String> fresh, Set<String> moved, Map<String, ModMixins> mods, SwitchApplier.Game game,
            ClassRebuilder rebuilder) throws RebuildException {
        Set<Class<?>> found = new LinkedHashSet<>();
        mods.values().forEach(mixins -> {
            boolean declares = fresh.stream().anyMatch(mixins.owns()::contains);
            if (!declares && moved.isEmpty()) {
                return;
            }
            for (String name : mixins.owns()) {
                Class<?> loaded = game.findLoaded(name);

                byte[] jar = loaded == null ? null : game.findOriginalBytes(name);
                if (loaded != null && (jar == null || DuckBridge.names(jar, declares ? fresh : Set.of(), moved))) {
                    found.add(loaded);
                }
            }
        });
        List<Class<?>> candidates = new ArrayList<>(found);
        if (candidates.isEmpty()) {
            return;
        }
        Set<String> ducks = Linker.getEverDucks();
        Map<Class<?>, byte[]> bridged = new LinkedHashMap<>();
        List<String> unread = new ArrayList<>();
        Map<String, byte[]> together;
        try {
            together = rebuilder.getLoadedBytes().readCurrent(candidates);
        } catch (RebuildException | RuntimeException | LinkageError e) {
            together = Map.of();
        }
        for (Class<?> type : candidates) {
            byte[] bytes = together.get(type.getName().replace('.', '/'));
            try {
                if (bytes == null) {
                    bytes = rebuilder.getLoadedBytes().readCurrent(List.of(type)).get(type.getName().replace('.', '/'));
                }
            } catch (RebuildException | RuntimeException | LinkageError e) {
                unread.add(type.getName() + " (" + e + ")");
                continue;
            }
            byte[] rewritten = bytes == null ? null : DuckBridge.bridgeInstructions(bytes, ducks);
            if (rewritten != null && rewritten != bytes) {
                bridged.put(type, rewritten);
            }
        }
        if (!unread.isEmpty()) {
            LOGGER.warn("[Hotwire] {} loaded classes could not be read to bridge them to the new ducks {}: {}",
                    unread.size(), fresh, unread.stream().limit(8).toList());
        }
        if (!bridged.isEmpty()) {
            LOGGER.info("[Hotwire] {} loaded classes cast to the new ducks {} or call the moved {} and are bridged: {}",
                    bridged.size(), fresh, moved, bridged.keySet().stream().map(Class::getName).limit(8).toList());
            redefineEachIfRefused(rebuilder, bridged);
        }
    }

    private static void redefineEachIfRefused(ClassRebuilder rebuilder, Map<Class<?>, byte[]> bridged)
            throws RebuildException {
        try {
            rebuilder.redefine(bridged);
            return;
        } catch (RebuildException batch) {
            LOGGER.warn("[Hotwire] the JVM refused the bridged classes as a batch; each goes alone: {}",
                    batch.getMessage());
        }
        List<String> refused = new ArrayList<>();
        for (Map.Entry<Class<?>, byte[]> entry : bridged.entrySet()) {
            try {
                rebuilder.redefine(Map.of(entry.getKey(), entry.getValue()));
            } catch (RebuildException e) {
                refused.add(entry.getKey().getName() + " (" + e.getCause() + ")");
                dumpRefused(entry.getKey().getName(), entry.getValue());
            }
        }
        if (!refused.isEmpty()) {
            LOGGER.error("[Hotwire] {} loaded classes could not be bridged and keep their old calls: {}",
                    refused.size(), refused.stream().limit(8).toList());
        }
    }

    private static void dumpRefused(String name, byte[] bytes) {
        String folder = System.getProperty("hotwire.dump");
        if (folder == null) {
            return;
        }
        try {
            Path base = Path.of(folder).resolve("refused-bridge");
            Files.createDirectories(base);
            Files.write(base.resolve(name + ".class"), bytes);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the refused class {} could not be written out", name, e);
        }
    }
}
