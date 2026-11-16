package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

final class ClassState {
    private static final Map<String, ClassState> ALL = new ConcurrentHashMap<>();

    volatile @Nullable Set<String> guarded;

    private @Nullable Set<String> staged;

    volatile @Nullable Set<String> present;
    private @Nullable Set<String> stagedPresent;

    final Set<String> directMods = ConcurrentHashMap.newKeySet();

    volatile @Nullable Set<String> putIn;

    volatile @Nullable Set<String> behaving;

    final Set<String> unflippable = ConcurrentHashMap.newKeySet();
    final Map<String, String> unflippableWhy = new ConcurrentHashMap<>();

    volatile @Nullable Set<String> unsited;
    volatile @Nullable Set<String> sited;

    volatile @Nullable Set<String> examined;

    volatile boolean rebuilt;

    private ClassState() {
    }

    static ClassState of(String internalName) {
        return ALL.computeIfAbsent(internalName, name -> new ClassState());
    }

    static @Nullable ClassState find(String internalName) {
        return ALL.get(internalName);
    }

    synchronized void setStaged(Set<String> mixins) {
        this.staged = mixins;
    }

    synchronized void setStagedPresent(Set<String> mixins) {
        this.stagedPresent = mixins;
    }

    synchronized @Nullable Set<String> takeStaged() {
        Set<String> was = this.staged;
        this.staged = null;
        return was;
    }

    synchronized @Nullable Set<String> takeStagedPresent() {
        Set<String> was = this.stagedPresent;
        this.stagedPresent = null;
        return was;
    }
}
