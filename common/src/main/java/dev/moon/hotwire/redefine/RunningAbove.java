package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassHeader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class RunningAbove {
    private RunningAbove() {
    }

    static Map<String, byte[]> add(Set<String> targets, Map<String, byte[]> classes,
            Function<String, byte @Nullable []> mixedNow, Function<Collection<String>, Map<String, byte[]>> runningOf,
            Function<String, byte @Nullable []> bytesOf) {
        Map<String, byte[]> out = new HashMap<>(classes);
        Set<String> seen = new HashSet<>(targets);
        Deque<String> next = new ArrayDeque<>(targets);
        List<String> toRead = new ArrayList<>();
        while (!next.isEmpty()) {
            String name = next.pop();
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                bytes = bytesOf.apply(name);
            }
            if (bytes == null) {
                continue;
            }
            for (String type : ClassHeader.of(bytes).findAbove()) {
                if (type.startsWith("java/") || !seen.add(type)) {
                    continue;
                }
                next.push(type);
                byte[] running = mixedNow.apply(type);
                if (running != null) {
                    out.put(type, running);
                } else {
                    toRead.add(type);
                }
            }
        }
        if (!toRead.isEmpty()) {
            out.putAll(runningOf.apply(toRead));
        }
        return out;
    }
}
