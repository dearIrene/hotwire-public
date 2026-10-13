package dev.moon.hotwire.fabric.rebuild;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConstantPools {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConstantPools.class);
    private static final byte FIELD = 9;
    private static final byte METHOD = 10;
    private static final byte INTERFACE_METHOD = 11;

    private final Object access;

    private final MethodHandle poolOf;
    private final MethodHandle oopOf;
    private final MethodHandle sizeOf;
    private final MethodHandle tagAt;
    private final MethodHandle ownerIndexAt;
    private final MethodHandle memberAt;

    private ConstantPools(Object access, MethodHandle poolOf, MethodHandle oopOf, MethodHandle sizeOf,
            MethodHandle tagAt, MethodHandle ownerIndexAt, MethodHandle memberAt) {
        this.access = access;
        this.poolOf = poolOf;
        this.oopOf = oopOf;
        this.sizeOf = sizeOf;
        this.tagAt = tagAt;
        this.ownerIndexAt = ownerIndexAt;
        this.memberAt = memberAt;
    }

    public static @Nullable ConstantPools open(Instrumentation instrumentation) {
        try {
            Set<Module> hotwire = Set.of(ConstantPools.class.getModule());
            instrumentation.redefineModule(Object.class.getModule(), Set.of(), Map.of("jdk.internal.access", hotwire),
                    Map.of("jdk.internal.reflect", hotwire), Set.of(), Map.of());
            Class<?> accessType = Class.forName("jdk.internal.access.JavaLangAccess");
            Class<?> pool = Class.forName("jdk.internal.reflect.ConstantPool");
            Object access = Class.forName("jdk.internal.access.SharedSecrets").getMethod("getJavaLangAccess")
                    .invoke(null);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(pool, MethodHandles.lookup());
            MethodType read = MethodType.methodType(void.class, Object.class, int.class);
            return new ConstantPools(access,
                    MethodHandles.lookup().findVirtual(accessType, "getConstantPool", MethodType.methodType(pool,
                            Class.class)).asType(MethodType.methodType(Object.class, Object.class, Class.class)),
                    lookup.findGetter(pool, "constantPoolOop", Object.class)
                            .asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.findVirtual(pool, "getSize0", MethodType.methodType(int.class, Object.class))
                            .asType(MethodType.methodType(int.class, Object.class, Object.class)),
                    lookup.findVirtual(pool, "getTagAt0", read.changeReturnType(byte.class))
                            .asType(MethodType.methodType(byte.class, Object.class, Object.class, int.class)),
                    lookup.findVirtual(pool, "getClassRefIndexAt0", read.changeReturnType(int.class))
                            .asType(MethodType.methodType(int.class, Object.class, Object.class, int.class)),
                    lookup.findVirtual(pool, "getMemberRefInfoAt0", read.changeReturnType(String[].class))
                            .asType(MethodType.methodType(String[].class, Object.class, Object.class, int.class)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[Hotwire] the JDK will not show classes' constant pools: {}", e.toString());
            return null;
        }
    }

    public List<String[]> getMembers(Class<?> type) {
        List<String[]> members = new ArrayList<>();
        this.readPool(type, (pool, oop, size) -> {
            for (int at = 1; at < size; at++) {
                if (isMember((byte) this.tagAt.invokeExact(pool, oop, at))) {
                    String[] member = (String[]) this.memberAt.invokeExact(pool, oop, at);
                    member[0] = member[0].replace('.', '/');
                    members.add(member);
                }
            }
            return true;
        });
        return members;
    }

    public boolean names(Class<?> type, Predicate<String> owner) {
        return this.readPool(type, (pool, oop, size) -> {
            Map<Integer, Boolean> asked = new HashMap<>();
            for (int at = 1; at < size; at++) {
                if (!isMember((byte) this.tagAt.invokeExact(pool, oop, at))) {
                    continue;
                }
                int index = (int) this.ownerIndexAt.invokeExact(pool, oop, at);
                Boolean named = asked.get(index);
                if (named == null) {
                    String[] member = (String[]) this.memberAt.invokeExact(pool, oop, at);
                    named = owner.test(member[0].replace('.', '/'));
                    asked.put(index, named);
                }
                if (named) {
                    return true;
                }
            }
            return false;
        });
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private boolean readPool(Class<?> type, PoolRead read) {
        try {
            Object pool = this.poolOf.invokeExact(this.access, type);
            Object oop = this.oopOf.invokeExact(pool);
            return read.read(pool, oop, (int) this.sizeOf.invokeExact(pool, oop));
        } catch (RuntimeException | Error e) {
            return false;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface PoolRead {
        boolean read(Object pool, Object oop, int size) throws Throwable;
    }

    private static boolean isMember(byte tag) {
        return tag == FIELD || tag == METHOD || tag == INTERFACE_METHOD;
    }
}
