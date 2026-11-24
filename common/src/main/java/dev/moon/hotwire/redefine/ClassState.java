package dev.moon.hotwire.redefine;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

final class ClassState {
    private static final Map<String, ClassState> ALL = new ConcurrentHashMap<>();

    volatile @Nullable Set<String> guarded;

    private @Nullable Set<String> staged;

    volatile @Nullable Set<String> present;
    private @Nullable Set<String> stagedPresent;

    private volatile @Nullable Set<String> directMods;

    volatile @Nullable Set<String> putIn;

    volatile @Nullable Set<String> behaving;

    private volatile @Nullable Set<String> unflippable;
    private volatile @Nullable Map<String, String> unflippableWhy;

    private volatile @Nullable Set<String> savesRun;
    private volatile @Nullable Map<String, String> savesLost;

    volatile @Nullable Set<String> unsited;
    volatile @Nullable Set<String> sited;

    volatile @Nullable Set<String> examined;

    volatile boolean rebuilt;

    private static final ClassState UNMIXED = new ClassState();

    static {
        UNMIXED.present = Set.of();
    }

    private ClassState() {
    }

    static ClassState of(String internalName) {
        ClassState found = ALL.get(internalName);
        if (found != null && found != UNMIXED) {
            return found;
        }
        return ALL.compute(internalName, (name, was) -> {
            if (was != null && was != UNMIXED) {
                return was;
            }
            ClassState made = new ClassState();
            made.present = was == UNMIXED ? Set.of() : null;
            return made;
        });
    }

    static boolean markUnmixed(String internalName) {
        ClassState was = ALL.putIfAbsent(internalName, UNMIXED);
        return was == null || was == UNMIXED;
    }

    static void forgetUnmixed(String internalName) {
        ALL.remove(internalName, UNMIXED);
    }

    static @Nullable ClassState find(String internalName) {
        return ALL.get(internalName);
    }

    synchronized void addDirectMod(String mod) {
        Set<String> mods = this.directMods;
        if (mods == null) {
            mods = ConcurrentHashMap.newKeySet();
            this.directMods = mods;
        }
        mods.add(mod);
    }

    synchronized void clearDirectMods() {
        Set<String> mods = this.directMods;
        if (mods != null) {
            mods.clear();
        }
    }

    synchronized void addUnflippable(String mixin, @Nullable String why) {
        Set<String> mixins = this.unflippable;
        if (mixins == null) {
            mixins = ConcurrentHashMap.newKeySet();
            this.unflippable = mixins;
        }
        mixins.add(mixin);
        if (why == null) {
            return;
        }
        Map<String, String> reasons = this.unflippableWhy;
        if (reasons == null) {
            reasons = new ConcurrentHashMap<>();
            this.unflippableWhy = reasons;
        }
        reasons.merge(mixin, why, (had, more) -> had.contains(more) ? had : had + ", " + more);
    }

    synchronized void addSaveRun(String mixin) {
        Set<String> mixins = this.savesRun;
        if (mixins == null) {
            mixins = ConcurrentHashMap.newKeySet();
            this.savesRun = mixins;
        }
        mixins.add(mixin);
    }

    synchronized void addSaveLost(String mixin, String why) {
        Map<String, String> mixins = this.savesLost;
        if (mixins == null) {
            mixins = new ConcurrentHashMap<>();
            this.savesLost = mixins;
        }
        mixins.putIfAbsent(mixin, why);
    }

    Set<String> getSavesRun() {
        Set<String> mixins = this.savesRun;
        return mixins == null ? Set.of() : mixins;
    }

    Map<String, String> getSavesLost() {
        Map<String, String> mixins = this.savesLost;
        return mixins == null ? Map.of() : mixins;
    }

    static void visitAll(BiConsumer<String, ClassState> action) {
        ALL.forEach(action);
    }

    Set<String> getDirectMods() {
        Set<String> mods = this.directMods;
        return mods == null ? Set.of() : mods;
    }

    Set<String> getUnflippable() {
        Set<String> mixins = this.unflippable;
        return mixins == null ? Set.of() : mixins;
    }

    Map<String, String> getUnflippableWhy() {
        Map<String, String> reasons = this.unflippableWhy;
        return reasons == null ? Map.of() : reasons;
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
