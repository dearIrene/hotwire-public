package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.InitialValues;
import dev.moon.hotwire.redefine.LoadedClassBytes;
import dev.moon.hotwire.redefine.RebuildException;
import dev.moon.hotwire.redefine.StaticRecomputation;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StaticRecomputer {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaticRecomputer.class);

    record Recomputation(FieldSlots.Slot field, @Nullable Object holder, Object old, Object made) {
    }

    interface Writer {
        void write(FieldSlots.Slot field, @Nullable Object holder, Object value) throws IllegalAccessException;
    }

    private final ClassRebuilder rebuilder;
    private final Function<Class<?>, @Nullable String> lambdaOwner;
    private final Function<String, List<String>> generatedBy;

    StaticRecomputer(ClassRebuilder rebuilder, Function<Class<?>, @Nullable String> lambdaOwner,
            Function<String, List<String>> generatedBy) {
        this.rebuilder = rebuilder;
        this.lambdaOwner = lambdaOwner;
        this.generatedBy = generatedBy;
    }

    List<Recomputation> recompute(String mod, Set<String> targets, Map<String, Class<?>> loaded,
            Predicate<Class<?>> others, Writer writer) {
        List<Recomputation> made = new ArrayList<>();

        Set<Object> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> generated = Set.copyOf(this.generatedBy.apply(mod));
        for (String target : targets) {
            Class<?> owner = loaded.get(target);
            byte[] launched = owner == null ? null : this.rebuilder.launchBytes(target);
            byte[] running = owner == null ? null : this.rebuilder.getLoadedBytes().findKnown(owner);
            if (owner == null || launched == null || running == null || !others.test(owner)) {
                continue;
            }
            for (String key : StaticRecomputation.findMadeWith(launched, mod)) {
                FieldSlots.Slot field = Fields.getDeclared(owner).stream()
                        .filter(one -> key.equals(one.getName() + one.getType().descriptorString())).findFirst()
                        .orElse(null);
                if (field == null || Collection.class.isAssignableFrom(field.getType())
                        || Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object old = field.get(null);
                    if (old == null || !this.reaches(old, mod, replaced, generated)) {
                        continue;
                    }
                    Object now = InitialValues.ofStatic(owner, running, field.getName(),
                            field.getType().descriptorString()).orElse(null);
                    if (now != null && old instanceof Enum<?>) {
                        made.addAll(this.giveFields(old, now, mod, replaced, generated, writer));
                    } else if (now != null) {
                        writer.write(field, null, now);
                        made.add(new Recomputation(field, null, old, now));
                        replaced.add(old);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOGGER.warn("[Hotwire] {}.{} keeps what {} made of it: {}", owner.getName(), field.getName(), mod,
                            e.toString());
                }
            }
        }
        return made;
    }

    @SuppressWarnings("ReferenceEquality")
    private List<Recomputation> giveFields(Object constant, Object fresh, String mod, Set<Object> replaced,
            Set<String> generated, Writer writer) throws IllegalAccessException {
        List<Recomputation> given = new ArrayList<>();
        for (FieldSlots.Slot field : Fields.getReferenceFields(constant.getClass(), false)) {
            Object old = field.get(constant);
            Object now = field.get(fresh);

            if (old != null && now != null && old != now && !(old instanceof Collection<?>)
                    && !(old instanceof Map<?, ?>) && this.reaches(old, mod, replaced, generated)) {
                writer.write(field, constant, now);
                given.add(new Recomputation(field, constant, old, now));
                replaced.add(old);
            }
        }
        return given;
    }

    List<Recomputation> recomputeReaders(List<Recomputation> first, @Nullable ConstantPools pools,
            Instrumentation instrumentation, Predicate<Class<?>> initialized, Writer writer) {
        Set<Object> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        first.forEach(one -> replaced.add(one.old()));
        List<Recomputation> made = new ArrayList<>();
        List<Recomputation> level = first;
        ClassLoader game = StaticRecomputer.class.getClassLoader();
        for (int depth = 0; depth < 6 && !level.isEmpty() && pools != null && made.size() < 300; depth++) {
            Set<String> names = new HashSet<>();
            level.forEach(one -> names.add(findNameRead(one)));
            List<Class<?>> readers = Arrays.<Class<?>>stream(instrumentation.getAllLoadedClasses()).parallel()
                    .filter(type -> Objects.equals(type.getClassLoader(), game) && !type.isHidden()
                            && !type.isArray() && pools.getMembers(type).stream()
                                    .anyMatch(member -> names.contains(member[0] + "." + member[1])))
                    .toList();
            List<Class<?>> set = readers.stream().filter(initialized).toList();

            try {
                LoadedClassBytes bytes = this.rebuilder.getLoadedBytes();
                bytes.read(set.stream().filter(reader -> bytes.findKnown(reader) == null).toList(), new ArrayList<>());
            } catch (RebuildException e) {
            }
            List<Recomputation> next = new ArrayList<>();
            for (Class<?> reader : set) {
                this.recomputeHolders(reader, replaced, writer, next);
            }
            made.addAll(next);
            level = next;
        }
        return made;
    }

    @SuppressWarnings("ReferenceEquality")
    private static String findNameRead(Recomputation one) {
        FieldSlots.Slot named = one.field();
        if (one.holder() instanceof Enum<?> constant) {
            for (FieldSlots.Slot field : Fields.getReferenceFields(constant.getDeclaringClass(), true)) {
                try {
                    if (field.get(null) == constant) {
                        named = field;
                    }
                } catch (IllegalAccessException e) {
                }
            }
        }
        return named.getDeclaringClass().getName().replace('.', '/') + "." + named.getName();
    }

    private void recomputeHolders(Class<?> reader, Set<Object> replaced, Writer writer, List<Recomputation> next) {
        String name = reader.getName().replace('.', '/');
        byte[] bytes = this.rebuilder.getLoadedBytes().findKnown(reader);
        try {
            bytes = bytes != null ? bytes : this.rebuilder.getLoadedBytes().readCurrent(List.of(reader)).get(name);
        } catch (RebuildException e) {
            return;
        }
        if (bytes == null) {
            return;
        }
        for (String key : StaticRecomputation.findStored(bytes)) {
            FieldSlots.Slot field = Fields.getDeclared(reader).stream()
                    .filter(one -> key.equals(one.getName() + one.getType().descriptorString())).findFirst()
                    .orElse(null);
            if (field == null || Collection.class.isAssignableFrom(field.getType())
                    || Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Object old = field.get(null);
                if (old == null || old instanceof Enum<?> || replaced.contains(old)
                        || !this.reaches(old, "", replaced, Set.of())) {
                    continue;
                }
                Object now = InitialValues.ofStatic(reader, bytes, field.getName(),
                        field.getType().descriptorString()).orElse(null);
                if (now != null) {
                    writer.write(field, null, now);
                    next.add(new Recomputation(field, null, old, now));
                    replaced.add(old);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}.{} keeps what it was made from: {}", reader.getName(), field.getName(),
                        e.toString());
            }
        }
    }

    private boolean reaches(Object start, String mod, Set<Object> replaced, Set<String> generated) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> step = List.of(start);
        for (int depth = 0; depth < 6 && !step.isEmpty() && seen.size() < 2000; depth++) {
            List<Object> next = new ArrayList<>();
            for (Object one : step) {
                Class<?> type = one.getClass();
                if (!seen.add(one) || (type.isArray() && !(one instanceof Object[]))) {
                    continue;
                }
                if (replaced.contains(one) || generated.contains(type.getName())
                        || mod.equals(GameRegistrations.findModOf(type))
                        || (type.isHidden() && mod.equals(this.lambdaOwner.apply(type)))) {
                    return true;
                }
                if (one instanceof Collection<?> items && items.size() < 1000) {
                    items.forEach(item -> addIfObject(next, item));
                } else if (one instanceof Map<?, ?> map && map.size() < 1000) {
                    map.forEach((key, value) -> {
                        addIfObject(next, key);
                        addIfObject(next, value);
                    });
                } else if (one instanceof Object[] array && array.length < 1000) {
                    for (Object item : array) {
                        addIfObject(next, item);
                    }
                } else if (!type.getName().startsWith("java.lang.")) {
                    for (FieldSlots.Slot field : Fields.getReferenceFields(type, false)) {
                        try {
                            addIfObject(next, field.get(one));
                        } catch (IllegalAccessException e) {
                        }
                    }
                }
            }
            step = next;
        }
        return false;
    }

    private static void addIfObject(List<Object> next, @Nullable Object value) {
        if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Enum<?>)) {
            next.add(value);
        }
    }
}
