package dev.moon.hotwire.update;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class LiveNames {
    static final String ROOT = "hotwire_live.";

    private static final Map<String, Set<String>> CLASSES = new ConcurrentHashMap<>();

    private LiveNames() {
    }

    static void learn(String tag, Collection<String> internalNames) {
        Set<String> names = CLASSES.computeIfAbsent(tag, any -> ConcurrentHashMap.newKeySet());
        internalNames.forEach(name -> names.add(name.replace('/', '.')));
    }

    public static @Nullable String findOwnName(@Nullable String name) {
        int at = name == null ? -1 : name.indexOf(ROOT);
        if (name == null || at < 0) {
            return name;
        }
        int end = name.indexOf('.', at + ROOT.length());
        return end < 0 ? name : name.substring(0, at) + name.substring(end + 1);
    }

    public static String findLiveName(String name, Class<?> caller) {
        String asking = caller.getName();
        if (!asking.startsWith(ROOT)) {
            return name;
        }
        String tag = asking.substring(ROOT.length(), asking.indexOf('.', ROOT.length()));
        Set<String> own = CLASSES.get(tag);
        return own != null && own.contains(name) ? ROOT + tag + "." + name : name;
    }

    public static Class<?> findClass(String name, Class<?> caller) throws ClassNotFoundException {
        return Class.forName(findLiveName(name, caller), true, caller.getClassLoader());
    }

    public static Class<?> findClass(String name, boolean initialize, @Nullable ClassLoader loader, Class<?> caller)
            throws ClassNotFoundException {
        return Class.forName(findLiveName(name, caller), initialize, loader);
    }

    public static Class<?> loadClass(ClassLoader loader, String name, Class<?> caller) throws ClassNotFoundException {
        return loader.loadClass(findLiveName(name, caller));
    }
}
