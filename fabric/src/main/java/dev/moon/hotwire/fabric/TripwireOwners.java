package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.jvm.LoadedClassList;
import dev.moon.hotwire.redefine.HotwireLoaders;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.redefine.TripwireSites;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TripwireOwners {
    private static final Logger LOGGER = LoggerFactory.getLogger(TripwireOwners.class);
    private final ModTripwire owner;

    TripwireOwners(ModTripwire owner) {
        this.owner = owner;
    }

    byte @Nullable [] armAtLoad(String name, @Nullable String source, byte[] bytes, ClassLoader loader) {
        synchronized (this.owner.sources) {
            String mod = source == null ? null : this.owner.sources.get(source);
            if (mod == null || TripwireSites.isArmed(name)) {
                return null;
            }
            if (ModTripwire.ARMING.get()) {
                this.owner.loadedWhileArming.put(name.replace('/', '.'), mod);
            } else {
                Tripwire.onLoading(mod, name);
            }
            byte[] wired = TripwireSites.arm(name, bytes, mod);
            if (wired == null) {
                wired = Tripwire.arm(bytes, mod);
                if (wired != null) {
                    this.owner.armedAtLoad.put(name, new ArmedClass(mod, bytes, wired));
                }
            }
            if (wired != null) {
                this.owner.rebuilder.getLoadedBytes().onDefinedInGame(name, wired, loader);
            }
            return wired;
        }
    }

    Map<String, byte[]> readBytes(List<Class<?>> types) {
        List<String> refused = new ArrayList<>();
        try {
            Map<String, byte[]> read = this.owner.rebuilder.getLoadedBytes().read(types, refused);
            if (!refused.isEmpty()) {
                LOGGER.info("[Hotwire] {} classes are left unarmed, as the JVM cannot give back their bytes: {}",
                        refused.size(), refused);
            }
            return read;
        } catch (RebuildException e) {
            LOGGER.info("[Hotwire] {} classes are left unarmed: {}", types.size(), e.getMessage());
            return Map.of();
        }
    }

    Map<Class<?>, String> findOwned(Collection<String> ids) {
        Map<Class<?>, String> owned = new LinkedHashMap<>();
        for (Class<?> type : LoadedClassList.of(this.owner.instrumentation)) {
            if (type.isArray() || type.isPrimitive() || !HotwireLoaders.seesHotwire(type.getClassLoader())) {
                continue;
            }
            String mod = GameRegistrations.findModOf(type);
            if (mod == null) {
                mod = findSplitOwner(type);
            }

            if (mod == null) {
                mod = GameRegistrations.findNestedModOf(type);
            }
            if (mod != null && ids.contains(mod)) {
                owned.put(type, mod);
            }
        }
        return owned;
    }

    static @Nullable String findSplitOwner(Class<?> type) {
        String name = type.getName();
        int split = name.indexOf("$hotwire");
        if (split < 0) {
            return null;
        }
        try {
            return GameRegistrations.findModOf(Class.forName(name.substring(0, split), false, type.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
