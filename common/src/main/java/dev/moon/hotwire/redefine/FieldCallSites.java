package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

final class FieldCallSites {
    record Bound(WeakReference<MutableCallSite> site, String name, MethodType type, int kind) {
    }

    private FieldCallSites() {
    }

    static CallSite bind(Class<?> target, String name, MethodType type, int kind)
            throws ReflectiveOperationException {
        Class<?> holder = FieldHolders.findHolderClassOf(target);
        MutableCallSite site = new MutableCallSite(Linker.findCastFieldHandle(target, name, type, kind));
        Bound entry = new Bound(new WeakReference<>(site), name, type, kind);

        Linker.LINKS.compute(target, (key, now) -> {
            Linker.Link link = now == null ? new Linker.Link() : now;
            link.fieldSites.add(entry);
            return link;
        });

        for (Class<?> now = FieldHolders.findHolderClassOf(target); now != holder;
                now = FieldHolders.findHolderClassOf(target)) {
            holder = now;
            try {
                site.setTarget(Linker.findCastFieldHandle(target, name, type, kind));
            } catch (ReflectiveOperationException | IllegalStateException gone) {
            }
        }
        return site;
    }

    static void rebind(Class<?> target) {
        Linker.Link link = Linker.LINKS.get(target);
        if (link == null) {
            return;
        }
        Queue<Bound> bound = link.fieldSites;
        bound.removeIf(entry -> entry.site().get() == null);
        List<MutableCallSite> changed = new ArrayList<>();
        for (Bound entry : bound) {
            MutableCallSite site = entry.site().get();
            if (site == null) {
                continue;
            }
            try {
                MethodHandle now = Linker.findCastFieldHandle(target, entry.name(), entry.type(), entry.kind());
                site.setTarget(now);
                changed.add(site);
            } catch (ReflectiveOperationException | IllegalStateException gone) {
            }
        }
        MutableCallSite.syncAll(changed.toArray(MutableCallSite[]::new));
    }
}
