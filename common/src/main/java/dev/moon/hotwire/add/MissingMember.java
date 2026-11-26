package dev.moon.hotwire.add;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class MissingMember {
    private static final Pattern FIELD = Pattern.compile("Class (\\S+) does not have member field '\\S+ (\\S+)'");
    private static final Pattern METHOD = Pattern.compile("'\\S+ ([^\\s(]+)\\.([^.\\s(]+)\\(.*\\)'");

    private MissingMember() {
    }

    static boolean isFailingAtLaunchToo(@Nullable Throwable cause, ClassLoader loader, Predicate<String> patched) {
        for (Throwable at = cause; at != null; at = at.getCause()) {
            boolean field = at instanceof NoSuchFieldError;
            if ((field || at instanceof NoSuchMethodError) && at.getMessage() != null) {
                Matcher named = (field ? FIELD : METHOD).matcher(at.getMessage());
                return named.find() && isMissingFromAll(named.group(1), named.group(2), field, loader, patched);
            }
        }
        return false;
    }

    private static boolean isMissingFromAll(String owner, String member, boolean field, ClassLoader loader,
            Predicate<String> patched) {
        try {
            Deque<Class<?>> left = new ArrayDeque<>();
            left.add(Class.forName(owner, false, loader));
            Set<Class<?>> seen = new HashSet<>();
            while (!left.isEmpty()) {
                Class<?> type = left.poll();
                if (!seen.add(type)) {
                    continue;
                }
                if (patched.test(type.getName().replace('.', '/')) || has(type, member, field)) {
                    return false;
                }
                if (type.getSuperclass() != null) {
                    left.add(type.getSuperclass());
                }
                left.addAll(Set.of(type.getInterfaces()));
            }
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean has(Class<?> type, String member, boolean field) {
        if (field) {
            for (Field declared : type.getDeclaredFields()) {
                if (declared.getName().equals(member)) {
                    return true;
                }
            }
            return false;
        }
        for (Method declared : type.getDeclaredMethods()) {
            if (declared.getName().equals(member)) {
                return true;
            }
        }
        return "<init>".equals(member);
    }
}
