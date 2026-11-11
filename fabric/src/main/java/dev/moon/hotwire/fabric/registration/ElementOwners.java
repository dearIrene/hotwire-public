package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.ModNames;
import java.lang.reflect.Member;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.ModContainer;
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

        String mod = GameRegistrations.findModOf(type);
        return !type.isHidden() && !type.isRecord() && !Proxy.isProxyClass(type)
                && (mod == null || mod.equals("minecraft"))
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

        String madeBy = ValueOwners.isLambda(item) ? GameRegistrations.findModOrShipper(item.getClass().getNestHost())
                : null;
        if (madeBy != null && !this.modSet.contains(madeBy) && ModPolicy.isSwitchable(madeBy)
                && ValueOwners.findForwardedTo(item) == null) {
            return inSmall ? this.findCapturedWrapperOf(item) : null;
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

        String captured = inSmall && ValueOwners.isLambda(item) ? this.findCapturedWrapperOf(item) : null;
        if (captured != null) {
            return captured;
        }

        String nested = GameRegistrations.findNestedModOf(item.getClass());
        return nested != null && this.modSet.contains(nested) ? nested : null;
    }

    private boolean isAskingCaptured;

    private @Nullable String findOwnerOfCaptured(Object part) {
        this.isAskingCaptured = true;
        try {
            return this.findOwnerOf(part, true);
        } finally {
            this.isAskingCaptured = false;
        }
    }

    private @Nullable String findCapturedWrapperOf(Object lambda) {
        String owner = null;
        try {
            for (FieldSlots.Slot field : Fields.getReferenceFields(lambda.getClass(), false)) {
                Object part = field.get(lambda);
                if (part == null || ValueOwners.isPlain(part) || ValueOwners.isGame(part)) {
                    continue;
                }

                @SuppressWarnings("ReferenceEquality")
                boolean itself = part == lambda;
                String of = itself || this.isAskingCaptured || ValueOwners.isLambda(part) ? null
                        : this.findOwnerOfCaptured(part);
                if (of == null || (owner != null && !owner.equals(of))) {
                    return null;
                }
                owner = of;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return owner;
    }

    Set<String> findRecordedCandidates(@Nullable Object item) {
        if (item == null) {
            return Set.of();
        }

        if (ValueOwners.isLambda(item)) {
            Set<String> found = new HashSet<>();
            for (FieldSlots.Slot captured : Fields.getReferenceFields(item.getClass(), false)) {
                Object part = CollectionParts.read(captured, item);
                if (part != null && !ValueOwners.isLambda(part)) {
                    found.addAll(this.findRecordedCandidates(part));
                }
            }
            return found;
        }
        String library = GameRegistrations.findModOf(item.getClass());
        boolean nested = library == null && GameRegistrations.findShippedBy(item.getClass()) != null;
        if (!nested && (library == null || !this.libraryOfSome.contains(library))) {
            return Set.of();
        }
        Set<String> found = new HashSet<>();
        for (FieldSlots.Slot field : Fields.getReferenceFields(item.getClass(), false)) {
            Object inner = CollectionParts.read(field, item);
            addRecorded(found, inner);
            if (inner != null && ValueOwners.isLambda(inner)) {
                for (FieldSlots.Slot captured : Fields.getReferenceFields(inner.getClass(), false)) {
                    addRecorded(found, CollectionParts.read(captured, inner));
                }
            }
        }
        return found;
    }

    private static void addRecorded(Set<String> found, @Nullable Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return;
        }

        if (value instanceof ModContainer container) {
            found.add(container.getMetadata().getId());
            return;
        }
        Class<?> type = value instanceof Member member ? member.getDeclaringClass()
                : value instanceof Class<?> named ? named : value.getClass();
        String mod = GameRegistrations.findModOf(type);
        mod = mod != null ? mod : GameRegistrations.findCodeMod(type);
        mod = mod != null ? mod : GameRegistrations.findNestedModOf(type);
        mod = mod != null || !type.isHidden() ? mod : ValueOwners.findLambdaOwner(type);
        if (mod != null) {
            found.add(mod);
        }
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

    boolean isAmong(String mod) {
        return this.modSet.contains(mod);
    }

    boolean isStayingModsType(@Nullable Object key) {
        String mod = key instanceof Class<?> type ? GameRegistrations.findModOf(type) : null;
        return mod != null && !this.modSet.contains(mod);
    }

    @Nullable String findWholeOwnerOfSlot(Object item) {
        String whole = this.findOwnerAmong(item);
        if (whole == null && ValueOwners.isLambda(item)) {
            whole = Wrappers.findFirstAmong(ValueOwners.findForwardedToAll(item), this.mods);
        }
        return whole;
    }

    @Nullable String findOwnerOfSlot(Object item) {
        String whole = this.findWholeOwnerOfSlot(item);
        return whole != null ? whole : this.findPartsOwnerOfSlot(item);
    }

    private @Nullable String findRecordedOwner(Object value, boolean nested) {
        if (value instanceof ModContainer container) {
            String id = container.getMetadata().getId();
            return this.modSet.contains(id) ? id : null;
        }
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
        if (Fields.getRecordParts(item.getClass()).isEmpty()) {
            return null;
        }
        Object first = Wrappers.findFirstPart(item);
        if (first == null) {
            return null;
        }
        boolean any = first instanceof Class<?> || first.getClass().isHidden();
        String ofClass = any ? null : GameRegistrations.findModOf(first.getClass());
        String ofId = any ? null : ValueOwners.findIdOwner(first);
        Set<String> wholly = Wrappers.findWhollyOfAll(item);
        if (!any) {
            wholly.retainAll(Arrays.asList(ofClass, ofId));
        }
        return Wrappers.findFirstAmong(wholly, this.mods);
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
