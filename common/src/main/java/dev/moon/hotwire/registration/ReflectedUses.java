package dev.moon.hotwire.registration;

import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.engine.SeenUses;
import dev.moon.hotwire.redefine.Tripwire;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ReflectedUses {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReflectedUses.class);
    private static final String DIRECT_HANDLE = "java.lang.invoke.DirectMethodHandle";

    private ReflectedUses() {
    }

    static void note(Class<?> holder, @Nullable Object value) {
        if (!(value instanceof Executable) && !(value instanceof MethodHandle)) {
            return;
        }
        Class<?> called = findCalled(value);
        String used = called == null ? null : Owners.findModOrShipper(called);
        String user = used == null ? null : Owners.findModOrShipper(holder);
        SeenUses seen = ModPolicy.getSeenUses();
        if (used == null || user == null || used.equals(user) || seen == null || !ModPolicy.isSwitchable(user)
                || !ModPolicy.isSwitchable(used) || isUsing(used, user) || isUsing(user, used)) {
            return;
        }
        if (seen.add(user, used)) {
            LOGGER.info("[Hotwire] {} has {}'s {} to call by its own hand: from now on it goes off with it", user,
                    used, value);
        }
    }

    static @Nullable Class<?> findCalled(Object value) {
        if (value instanceof Executable executable) {
            return executable.getDeclaringClass();
        }
        if (!(value instanceof MethodHandle handle) || !value.getClass().getName().startsWith(DIRECT_HANDLE)) {
            return null;
        }
        try {
            Member member = MethodHandles.reflectAs(Member.class, handle);
            return member instanceof Executable ? member.getDeclaringClass() : null;
        } catch (IllegalArgumentException | ClassCastException | SecurityException e) {
            return null;
        }
    }

    private static boolean isUsing(String mod, String other) {
        Set<String> seen = new HashSet<>();
        Deque<String> next = new ArrayDeque<>(Set.of(mod));
        while (!next.isEmpty()) {
            for (String one : Tripwire.getLibrariesOf(next.poll())) {
                if (one.equals(other)) {
                    return true;
                }
                if (seen.add(one)) {
                    next.add(one);
                }
            }
        }
        return false;
    }
}
