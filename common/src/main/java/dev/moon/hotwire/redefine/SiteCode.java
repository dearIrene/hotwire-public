package dev.moon.hotwire.redefine;

import dev.moon.hotwire.cache.CacheKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;

public final class SiteCode {
    private static final String OWN = "dev/moon/hotwire/";
    private static final String ROOT = OWN + "redefine/SiteCache";

    private static final byte CLASS_ENTRY = 7;

    private SiteCode() {
    }

    public static long hash(Function<String, byte @Nullable []> classBytes) {
        Map<String, byte[]> found = new TreeMap<>();
        Deque<String> next = new ArrayDeque<>(List.of(ROOT));
        while (!next.isEmpty()) {
            String name = next.poll();
            byte[] bytes = found.containsKey(name) ? null : classBytes.apply(name);
            if (bytes == null) {
                continue;
            }
            found.put(name, bytes);
            ClassReader reader = new ClassReader(bytes);
            char[] buffer = new char[reader.getMaxStringLength()];
            for (int item = 1; item < reader.getItemCount(); item++) {
                int offset = reader.getItem(item);
                if (offset == 0 || bytes[offset - 1] != CLASS_ENTRY) {
                    continue;
                }
                String named = reader.readUTF8(offset, buffer);

                int own = named.indexOf(OWN);
                if (own >= 0 && (own == 0 || named.charAt(0) == '[')) {
                    next.add(named.charAt(0) == '[' ? named.substring(own, named.length() - 1) : named);
                }
            }
        }
        CacheKey.Builder hash = CacheKey.of("the site code");
        found.forEach((name, bytes) -> hash.add(name).add(bytes));
        return hash.add(found.size()).build().low();
    }
}
