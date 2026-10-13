package dev.moon.hotwire.redefine;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;

final class DuckParents {
    private DuckParents() {
    }

    static Set<String> close(Collection<String> gained, Collection<String> had,
            Function<String, byte @Nullable []> classBytes) {
        Set<String> all = new LinkedHashSet<>(gained);
        all.removeAll(had);
        Deque<String> open = new ArrayDeque<>(all);
        while (!open.isEmpty()) {
            byte[] bytes = classBytes.apply(open.remove());
            if (bytes == null) {
                continue;
            }
            for (String parent : new ClassReader(bytes).getInterfaces()) {
                if (!had.contains(parent) && !isShared(parent) && all.add(parent)) {
                    open.add(parent);
                }
            }
        }
        return all;
    }

    private static boolean isShared(String name) {
        return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/")
                || name.startsWith("net/minecraft/") || name.startsWith("com/mojang/");
    }
}
