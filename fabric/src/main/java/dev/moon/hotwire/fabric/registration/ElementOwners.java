package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.ModNames;
import java.lang.reflect.Member;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

final class ElementOwners {
    private final List<String> mods;
    private final Set<String> modSet;

    private final Set<String> libraryOfSome;
    private final Set<Object> registered;

    private final ModNames names;

    private final Wrappers wrappers = new Wrappers();

    ElementOwners(List<String> mods, Set<Object> registered) {
        this.mods = List.copyOf(mods);
        this.modSet = Set.copyOf(mods);
        Set<String> libraries = new HashSet<>();
        mods.forEach(mod -> libraries.addAll(Tripwire.getLibrariesOf(mod)));
        this.libraryOfSome = Set.copyOf(libraries);
        this.registered = registered;
        this.names = new ModNames(mods);
    }

    boolean isRegistered(@Nullable Object value) {
        return value != null && this.registered.contains(value);
    }

    ModNames getNames() {
        return this.names;
    }

    Wrappers getWrappers() {
        return this.wrappers;
    }

    static boolean isGameObject(Object item) {
        Class<?> type = item.getClass();
        String name = type.getName();
        return !type.isHidden() && !type.isRecord() && !Proxy.isProxyClass(type)
                && GameRegistrations.findModOf(type) == null
                && (name.startsWith("net.minecraft.") || name.startsWith("com.mojang."));
    }

    @Nullable String findOwnerAmong(Object value) {
        String own = this.findOwnOwner(value);
        if (own != null) {
            return own;
        }

        if (isGameObject(value)) {
            return null;
        }
        String named = ReflectedOwners.findOwnerOf(value instanceof Class<?> type ? type : value.getClass());
        return named != null && this.modSet.contains(named) ? named : null;
    }

    @Nullable String findOwnOwner(Object value) {
        Class<?> type = value instanceof Class<?> named ? named : value.getClass();
        String mod = GameRegistrations.findModOf(type);
        if (mod != null && this.modSet.contains(mod)) {
            return mod;
        }

        String code = mod == null ? GameRegistrations.findCodeMod(type) : null;
        if (code != null && this.modSet.contains(code)) {
            return code;
        }
        String lambda = type.isHidden() ? ValueOwners.findLambdaOwner(type) : null;
        return lambda != null && this.modSet.contains(lambda) ? lambda : null;
    }

    @Nullable String findOwnerOf(Object item, boolean inSmall) {
        if (this.registered.contains(item)) {
            return null;
        }

        if (isGameObject(item)) {
            return null;
        }
        String owner = this.findOwnerAmong(item);
        if (owner != null) {
            return owner;
        }

        if (Proxy.isProxyClass(item.getClass())) {
            String handler = this.findOwnerAmong(Proxy.getInvocationHandler(item));
            if (handler != null) {
                return handler;
            }
        }

        if (item instanceof Member member) {
            String declaring = this.findOwnerAmong(member.getDeclaringClass());
            if (declaring != null) {
                return declaring;
            }
        }

        String whole = !Fields.getRecordParts(item.getClass()).isEmpty() ? Wrappers.findWhollyOwner(item)
                : ValueOwners.isLambda(item) ? ValueOwners.findForwardedTo(item) : null;
        if (whole != null && this.modSet.contains(whole)) {
            return whole;
        }
        String registered = this.findRegisteredBy(item);
        if (registered != null) {
            return registered;
        }
        String wrapped = inSmall ? this.wrappers.find(item, this.mods) : null;
        if (wrapped != null) {
            return wrapped;
        }

        String nested = GameRegistrations.findNestedModOf(item.getClass());
        return nested != null && this.modSet.contains(nested) ? nested : null;
    }

    private @Nullable String findRegisteredBy(Object item) {
        String library = GameRegistrations.findModOf(item.getClass());
        boolean nested = library == null && GameRegistrations.findShippedBy(item.getClass()) != null;
        if (!nested && (library == null || !this.libraryOfSome.contains(library))) {
            return null;
        }

        String self = nested ? GameRegistrations.findNestedModOf(item.getClass()) : library;
        for (FieldSlots.Slot field : Fields.getReferenceFields(item.getClass(), false)) {
            try {
                Object inner = field.get(item);
                String mod = inner == null ? null : this.findRecordedOwner(inner, nested);
                mod = mod != null && mod.equals(self) ? null : mod;
                if (mod == null && inner != null && ValueOwners.isLambda(inner)) {
                    for (FieldSlots.Slot captured : Fields.getReferenceFields(inner.getClass(), false)) {
                        Object value = captured.get(inner);
                        String of = mod != null || value == null ? null : this.findRecordedOwner(value, nested);
                        mod = of != null && !of.equals(self) ? of : mod;
                    }
                }

                if (mod != null && (nested
                        || (!mod.equals(library) && Tripwire.getLibrariesOf(mod).contains(library)))) {
                    return mod;
                }
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    @Nullable String findOwnerOfEntry(@Nullable Object key, @Nullable Object element, boolean inSmall) {
        if ((key != null && this.registered.contains(key)) || (element != null && this.registered.contains(element))) {
            return null;
        }
        if (this.isStayingModsType(key)) {
            return element == null ? null : this.findOwnOwner(element);
        }
        String mod = key == null ? null : this.findOwnerAmong(key);
        if (mod == null && element != null) {
            mod = this.findOwnerOf(element, inSmall);
        }
        return mod != null ? mod : Wrappers.findNamed(key, element, this.names);
    }

    boolean isStayingModsType(@Nullable Object key) {
        String mod = key instanceof Class<?> type ? GameRegistrations.findModOf(type) : null;
        return mod != null && !this.modSet.contains(mod);
    }

    @Nullable String findWholeOwnerOfSlot(Object item) {
        String whole = this.findOwnerAmong(item);
        if (whole == null && ValueOwners.isLambda(item)) {
            whole = this.mods.stream().filter(mod -> ValueOwners.forwardsTo(mod, item)).findFirst().orElse(null);
        }
        return whole;
    }

    @Nullable String findOwnerOfSlot(Object item) {
        String whole = this.findWholeOwnerOfSlot(item);
        return whole != null ? whole : this.findPartsOwnerOfSlot(item);
    }

    private @Nullable String findRecordedOwner(Object value, boolean nested) {
        Class<?> type = value instanceof Member member ? member.getDeclaringClass() : null;
        String mod = this.findOwnOwner(type != null ? type : value);
        if (mod != null || !nested) {
            return mod;
        }
        String of = GameRegistrations.findNestedModOf(type != null ? type
                : value instanceof Class<?> named ? named : value.getClass());
        return of != null && this.modSet.contains(of) ? of : null;
    }

    @Nullable String findPartsOwnerOfSlot(Object item) {
        return Fields.getRecordParts(item.getClass()).isEmpty() ? null
                : this.mods.stream().filter(mod -> Wrappers.isWhollyOf(mod, item)).findFirst().orElse(null);
    }

    @Nullable String findModNamespaceOf(@Nullable Object key, @Nullable Object value) {
        String named = CollectionParts.findNamespaceOf(key);
        if (named == null || this.modSet.contains(named)) {
            return named;
        }
        if (value == null || value instanceof Identifier) {
            return null;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(value.getClass(), false)) {
            try {
                String inner = CollectionParts.findNamespaceOf(field.get(value));
                if (inner != null && this.modSet.contains(inner)) {
                    return inner;
                }
            } catch (IllegalAccessException e) {
            }
        }
        return null;
    }
}
