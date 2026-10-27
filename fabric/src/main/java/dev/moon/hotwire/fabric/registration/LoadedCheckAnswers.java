package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.InitialValues;
import dev.moon.hotwire.redefine.LoadedChecks;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoadedCheckAnswers {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadedCheckAnswers.class);
    private static final Set<String> ASKS = Set.of("isModLoaded", "isLoaded", "getModContainer");

    private static final Set<String> LOOKUPS = Set.of("forName", "loadClass", "getMethod", "getDeclaredMethod",
            "getField", "getDeclaredField");

    private record ClearedFlag(FieldSlots.Slot field, Object old, @Nullable Object put) {
    }

    private final Instrumentation instrumentation;
    private final Function<String, byte @Nullable []> originalBytes;
    private final StaticRecomputer.Writer writer;
    private final Map<String, List<ClearedFlag>> cleared = new HashMap<>();

    LoadedCheckAnswers(Instrumentation instrumentation, Function<String, byte @Nullable []> originalBytes,
            StaticRecomputer.Writer writer) {
        this.instrumentation = instrumentation;
        this.originalBytes = originalBytes;
        this.writer = writer;
    }

    private record CheckSites(Map<String, List<FoundAsk>> asks, List<Class<?>> lookers) {
    }

    private record FoundAsk(Class<?> type, LoadedChecks.Ask ask) {
    }

    private volatile Set<String> aheadFor = Set.of();
    private volatile @Nullable CheckSites ahead;

    private volatile Set<String> everyFor = Set.of();
    private volatile @Nullable CheckSites every;

    void readForEvery(Collection<String> mods, @Nullable ConstantPools pools) {
        if (pools != null && !mods.isEmpty()) {
            this.every = this.find(mods, pools, type -> true);
            this.everyFor = Set.copyOf(mods);
        }
    }

    void readAhead(Collection<String> mods, @Nullable ConstantPools pools, Predicate<Class<?>> others) {
        if (pools != null && !mods.isEmpty()) {
            this.ahead = this.find(mods, pools, others);
            this.aheadFor = Set.copyOf(mods);
        }
    }

    void match(Collection<String> mods, boolean off, @Nullable ConstantPools pools, Predicate<Class<?>> others) {
        if (pools == null || mods.isEmpty()) {
            return;
        }
        CheckSites read = this.ahead;
        CheckSites all = this.every;
        CheckSites found;
        if (read != null && this.aheadFor.equals(Set.copyOf(mods))) {
            found = read;
        } else if (all != null && this.everyFor.containsAll(mods)) {
            found = all;
        } else {
            found = this.find(mods, pools, others);
        }
        List<String> changed = new ArrayList<>();
        if (off) {
            found.lookers().stream().filter(others).forEach(type -> this.clearLookups(type, mods, changed));
        }
        for (String mod : mods) {
            if (!off) {
                this.giveBack(mod, changed);
            }
            for (FoundAsk one : found.asks().getOrDefault(mod, List.of())) {
                if (!others.test(one.type())) {
                    continue;
                }
                byte[] bytes = this.originalBytes.apply(one.type().getName().replace('.', '/'));
                if (bytes != null) {
                    this.match(one.type(), bytes, one.ask(), mod, off, changed);
                }
            }
        }
        if (!changed.isEmpty()) {
            LOGGER.info("[Hotwire] what {} worked out from whether {} is there now agrees with its {}: {}",
                    changed.size(), mods, off ? "off" : "on", changed);
        }
    }

    private CheckSites find(Collection<String> mods, ConstantPools pools, Predicate<Class<?>> others) {
        ClassLoader game = LoadedCheckAnswers.class.getClassLoader();
        List<Class<?>> candidates = Arrays.<Class<?>>stream(this.instrumentation.getAllLoadedClasses()).parallel()
                .filter(type -> Objects.equals(type.getClassLoader(), game) && !type.isHidden() && !type.isArray()
                        && others.test(type) && pools.getMembers(type).stream()
                                .anyMatch(member -> ASKS.contains(member[1]) || LOOKUPS.contains(member[1])))
                .toList();
        List<Class<?>> lookers = candidates.stream().filter(type -> pools.getMembers(type).stream()
                .anyMatch(member -> LOOKUPS.contains(member[1]))).toList();
        Map<String, List<FoundAsk>> asks = new HashMap<>();
        for (Class<?> type : candidates) {
            if (pools.getMembers(type).stream().noneMatch(member -> ASKS.contains(member[1]))) {
                continue;
            }
            byte[] bytes = this.originalBytes.apply(type.getName().replace('.', '/'));
            if (bytes == null) {
                continue;
            }
            for (String mod : LoadedChecks.findNamed(bytes, Set.copyOf(mods))) {
                for (LoadedChecks.Ask ask : LoadedChecks.find(bytes, Set.of(mod))) {
                    asks.computeIfAbsent(mod, any -> new ArrayList<>()).add(new FoundAsk(type, ask));
                }
            }
        }
        return new CheckSites(asks, lookers);
    }

    private void match(Class<?> type, byte[] bytes, LoadedChecks.Ask ask, String mod, boolean off,
            List<String> changed) {
        FieldSlots.Slot field = Fields.getDeclared(type).stream()
                .filter(one -> ask.field().equals(one.getName() + one.getType().descriptorString())).findFirst()
                .orElse(null);
        if (field == null || !field.isStatic()) {
            return;
        }
        try {
            Object old = field.get(null);
            Object now = switch (ask.kind()) {
                case SETUP -> InitialValues.ofStatic(type, bytes, field.getName(), field.getType().descriptorString())
                        .orElse(null);
                case REMEMBERED -> (Object) false;
                case SET_ONCE -> off ? (Object) false : null;
            };
            if (now == null || now.equals(old)) {
                return;
            }
            this.writer.write(field, null, now);
            changed.add(type.getSimpleName() + "." + field.getName());
            if (ask.kind() == LoadedChecks.Kind.SET_ONCE && old != null) {
                this.cleared.computeIfAbsent(mod, any -> new ArrayList<>()).add(new ClearedFlag(field, old, now));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {}.{} keeps what it worked out from whether {} is there: {}", type.getName(),
                    field.getName(), mod, e.toString());
        }
    }

    private void clearLookups(Class<?> type, Collection<String> mods, List<String> changed) {
        byte[] bytes = this.originalBytes.apply(type.getName().replace('.', '/'));
        if (bytes == null) {
            return;
        }
        for (FieldSlots.Slot field : Fields.getReferenceFields(type, true)) {
            try {
                Object value = field.get(null);
                if (value == null) {
                    continue;
                }
                String mod = GameRegistrations.findModOf(findOwner(value));
                String descriptor = field.getType().descriptorString();

                boolean added = !ClassFileSummary.isDeclaredBy(bytes, field.getName(), descriptor);
                if (mod == null || !mods.contains(mod) || Modifier.isFinal(field.getModifiers())
                        || !(added || ClassFileSummary.isSetAgainLater(bytes, field.getName(), descriptor, true))) {
                    continue;
                }
                this.writer.write(field, null, null);
                changed.add(type.getSimpleName() + "." + field.getName());
                this.cleared.computeIfAbsent(mod, any -> new ArrayList<>()).add(new ClearedFlag(field, value, null));
            } catch (IllegalAccessException | RuntimeException e) {
                LOGGER.warn("[Hotwire] {}.{} keeps what it looked up: {}", type.getName(), field.getName(),
                        e.toString());
            }
        }
    }

    private static Class<?> findOwner(Object value) {
        return switch (value) {
            case Member member -> member.getDeclaringClass();
            case Class<?> type -> type;
            default -> value.getClass();
        };
    }

    private void giveBack(String mod, List<String> changed) {
        for (ClearedFlag one : this.cleared.getOrDefault(mod, List.of())) {
            try {
                if (Objects.equals(one.field().get(null), one.put())) {
                    this.writer.write(one.field(), null, one.old());
                    changed.add(one.field().getDeclaringClass().getSimpleName() + "." + one.field().getName());
                }
            } catch (IllegalAccessException | RuntimeException e) {
                LOGGER.warn("[Hotwire] {} could not be given back what it held: {}", one.field(), e.toString());
            }
        }
        this.cleared.remove(mod);
    }
}
