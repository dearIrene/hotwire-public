package dev.moon.hotwire.redefine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinTask;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;

final class JdkContracts {
    private static final Set<Class<?>> TASKS = Set.of(Runnable.class, Callable.class, Thread.class, TimerTask.class,
            InvocationHandler.class, Thread.UncaughtExceptionHandler.class, Flow.Subscriber.class,
            CompletionHandler.class);

    private static final ClassValue<Set<String>> ASKED = new ClassValue<>() {
        @Override
        protected Set<String> computeValue(Class<?> type) {
            Set<String> asked = new HashSet<>();
            Set<String> tasks = new HashSet<>();
            collect(type, asked, tasks, new HashSet<>());
            asked.removeAll(tasks);
            return Set.copyOf(asked);
        }
    };

    private JdkContracts() {
    }

    static Set<String> find(@Nullable String superName, List<String> interfaces) {
        Set<String> asked = Set.of();
        if (superName != null) {
            asked = join(asked, findOf(superName));
        }
        for (String one : interfaces) {
            asked = join(asked, findOf(one));
        }
        return asked;
    }

    static Set<String> find(Class<?> type) {
        Class<?> above = type.getSuperclass();
        List<String> interfaces = new ArrayList<>();
        for (Class<?> one : type.getInterfaces()) {
            interfaces.add(Type.getInternalName(one));
        }
        return find(above == null ? null : Type.getInternalName(above), interfaces);
    }

    private static Set<String> join(Set<String> some, Set<String> more) {
        if (more.isEmpty()) {
            return some;
        }
        if (some.isEmpty()) {
            return more;
        }
        Set<String> both = new HashSet<>(some);
        both.addAll(more);
        return both;
    }

    private static Set<String> findOf(String internal) {
        if (!internal.startsWith("java/") || internal.equals("java/lang/Object") || internal.equals("java/lang/Record")
                || internal.equals("java/lang/Enum")) {
            return Set.of();
        }
        try {
            return ASKED.get(Class.forName(internal.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Set.of();
        }
    }

    private static void collect(Class<?> type, Set<String> asked, Set<String> tasks, Set<Class<?>> seen) {
        if (type.equals(Object.class) || !seen.add(type)) {
            return;
        }
        boolean task = isTask(type);
        for (Method method : type.getDeclaredMethods()) {
            int access = method.getModifiers();
            if (Modifier.isStatic(access) || Modifier.isFinal(access)
                    || !(Modifier.isPublic(access) || Modifier.isProtected(access))) {
                continue;
            }
            (task ? tasks : asked).add(method.getName() + Type.getMethodDescriptor(method));
        }
        Class<?> above = type.getSuperclass();
        if (above != null) {
            collect(above, asked, tasks, seen);
        }
        for (Class<?> one : type.getInterfaces()) {
            collect(one, asked, tasks, seen);
        }
    }

    private static boolean isTask(Class<?> type) {
        return TASKS.contains(type) || EventListener.class.isAssignableFrom(type)
                || ForkJoinTask.class.isAssignableFrom(type) || type.getPackageName().equals("java.util.function");
    }
}
