package dev.moon.hotwire.update;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class LiveNames {
    static final String ROOT = "hotwire_live.";

    private static final Map<String, Set<String>> CLASSES = new ConcurrentHashMap<>();

    private static final List<String> RUNNING = new CopyOnWriteArrayList<>();

    private static final Map<String, Set<String>> OWN_IDS = new ConcurrentHashMap<>();
    private static final Pattern LIVE_FOLDER = Pattern.compile("hotwire_live/[^/;]+/");

    private LiveNames() {
    }

    static void learnIds(Map<String, String> liveIdOfId) {
        liveIdOfId.forEach((id, live) -> OWN_IDS.computeIfAbsent(live, any -> ConcurrentHashMap.newKeySet())
                .add(id));
    }

    public static Set<String> findOwnIds(String liveId) {
        return OWN_IDS.getOrDefault(liveId, Set.of());
    }

    public static List<String> findCopiesOf(String id) {
        return OWN_IDS.entrySet().stream().filter(copy -> copy.getValue().contains(id)).map(Map.Entry::getKey)
                .toList();
    }

    public static Set<String> findOwnKeys(String key) {
        int at = key.indexOf('$');
        Set<String> ids = at < 0 ? null : OWN_IDS.get(key.substring(0, at));
        if (ids == null) {
            return Set.of();
        }
        String rest = LIVE_FOLDER.matcher(key.substring(at)).replaceAll("");
        Set<String> keys = new HashSet<>();
        ids.forEach(id -> keys.add(id + rest));
        return keys;
    }

    public static void setRunning(String tag, boolean runs) {
        RUNNING.remove(tag);
        if (runs) {
            RUNNING.add(tag);
        }
    }

    public static String findRunningName(String name) {
        Object[] tags = RUNNING.toArray();
        for (int at = tags.length - 1; at >= 0; at--) {
            String tag = (String) tags[at];
            Set<String> own = CLASSES.get(tag);
            if (own != null && own.contains(name)) {
                return ROOT + tag + "." + name;
            }
        }
        return name;
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

    public static Class<?> findRunning(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(findRunningName(name), false, loader);
    }

    public static Class<?> findBeside(String name, Class<?> other) throws ClassNotFoundException {
        return Class.forName(findLiveName(name, other), false, other.getClassLoader());
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
