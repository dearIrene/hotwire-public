package dev.moon.hotwire.update;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PinnedClasses {
    private final Set<String> names;
    private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
    private final Map<String, byte[]> before = new ConcurrentHashMap<>();

    public PinnedClasses(Set<String> internalNames) {
        this.names = Set.copyOf(internalNames);
    }

    boolean has(String internalName) {
        return this.names.contains(internalName);
    }

    void put(String internalName, byte[] made, byte[] runs) {
        this.bodies.put(internalName, made);
        this.before.put(internalName, runs);
    }

    public Map<String, byte[]> getBodies() {
        return Map.copyOf(this.bodies);
    }

    public Map<String, byte[]> getBefore() {
        return Map.copyOf(this.before);
    }
}
