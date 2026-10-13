package dev.moon.hotwire.redefine;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RebuildCache {
    record Inputs(Map<Class<?>, byte[]> originals, Map<String, byte[]> current, Map<String, byte[]> classes,
            Map<String, byte[]> resources, List<String> on, boolean toLaunch, Set<String> madeWithout) {
        Inputs {
            madeWithout = Set.copyOf(madeWithout);
            originals = Map.copyOf(originals);
            current = Map.copyOf(current);
            classes = Map.copyOf(classes);
            resources = Map.copyOf(resources);
            on = List.copyOf(on);
        }

        private @Nullable String findDifference(Inputs other) {
            if (!this.on.equals(other.on)) {
                return "";
            }
            if (this.toLaunch != other.toLaunch) {
                return "whether it goes back to launch";
            }
            if (!this.madeWithout.equals(other.madeWithout)) {
                return "the classes with objects made while a mod was off";
            }
            String differs = findDifference("the original", this.originals, other.originals);
            differs = differs != null ? differs : findDifference("the running", this.current, other.current);
            differs = differs != null ? differs : findDifference("the handed", this.classes, other.classes);
            return differs != null ? differs : findDifference("the config", this.resources, other.resources);
        }

        private static <K> @Nullable String findDifference(String what, Map<K, byte[]> one, Map<K, byte[]> two) {
            for (K key : one.keySet()) {
                if (!two.containsKey(key)) {
                    return what + " " + key + " only ahead";
                }
            }
            for (K key : two.keySet()) {
                if (!one.containsKey(key)) {
                    return what + " " + key + " only now";
                }
            }

            for (Map.Entry<K, byte[]> entry : one.entrySet()) {
                if (!Arrays.equals(entry.getValue(), two.get(entry.getKey()))) {
                    return what + " bytes of " + entry.getKey();
                }
            }
            return null;
        }
    }

    private record Entry(Inputs inputs, ClassRebuilder.PreparedRebuild prepared) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildCache.class);

    private volatile @Nullable Entry last;

    ClassRebuilder.@Nullable PreparedRebuild find(Inputs inputs) {
        Entry entry = this.last;
        if (entry == null) {
            return null;
        }
        String differs = entry.inputs().findDifference(inputs);
        if (differs == null) {
            return entry.prepared();
        }
        if (!differs.isEmpty()) {
            LOGGER.info("[Hotwire] the rebuild worked out ahead read other bytes: {}", differs);
        }
        return null;
    }

    void store(Inputs inputs, ClassRebuilder.PreparedRebuild prepared) {
        this.last = new Entry(inputs, prepared);
    }

    void clear() {
        this.last = null;
    }
}
