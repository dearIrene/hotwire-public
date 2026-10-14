package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.CrashGuard;
import dev.moon.hotwire.fabric.rebuild.ConstantPools;
import dev.moon.hotwire.redefine.FieldSlots;
import dev.moon.hotwire.redefine.Linker;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

final class Lambdas {
    private final Instrumentation instrumentation;
    private boolean asked;
    private @Nullable ConstantPools pools;

    private final ClassValue<Optional<String>> told = new ClassValue<>() {
        @Override
        protected Optional<String> computeValue(Class<?> type) {
            return Optional.ofNullable(Lambdas.this.tell(type));
        }
    };

    Lambdas(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Nullable String findModOf(Class<?> type) {
        return this.told.get(type).orElse(null);
    }

    private @Nullable String tell(Class<?> type) {
        Method spun = type.isHidden() ? Linker.findSpunBody(type) : null;
        if (spun != null) {
            return CrashGuard.findMergedMod(spun);
        }
        if (!type.isHidden() || !type.getName().contains("$$Lambda")) {
            return null;
        }
        Class<?> host = type.getNestHost();
        String target = this.findTarget(type, host);
        if (target == null) {
            return this.findModCalled(type, host);
        }
        try {
            for (Method method : host.getDeclaredMethods()) {
                if (method.getName().equals(target)) {
                    return CrashGuard.findMergedMod(method);
                }
            }
        } catch (LinkageError e) {
        }
        return null;
    }

    private @Nullable String findModCalled(Class<?> lambda, Class<?> host) {
        ConstantPools opened = this.open();
        if (opened == null) {
            return null;
        }
        String hostName = host.getName().replace('.', '/');
        for (String[] member : opened.getMembers(lambda)) {
            if (member[0].equals(hostName) || member[1].startsWith("<")) {
                continue;
            }
            try {
                String mod = GameRegistrations.findModOf(Class.forName(member[0].replace('/', '.'), false,
                        lambda.getClassLoader()));
                if (mod != null) {
                    return mod;
                }
            } catch (ClassNotFoundException | LinkageError e) {
            }
        }
        return null;
    }

    static @Nullable Object findWrapped(Object lambda, Class<?> type, String mod) {
        Object found = null;
        for (FieldSlots.Slot field : Fields.getDeclared(lambda.getClass())) {
            if (field.isStatic() || !type.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Object value = field.get(lambda);
                if (value != null && !mod.equals(GameRegistrations.findModOf(value.getClass()))) {
                    if (found != null) {
                        return null;
                    }
                    found = value;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return found;
    }

    private @Nullable String findTarget(Class<?> lambda, Class<?> host) {
        ConstantPools opened = this.open();
        if (opened == null) {
            return null;
        }
        String hostName = host.getName().replace('.', '/');
        for (String[] member : opened.getMembers(lambda)) {
            if (member[0].equals(hostName) && !member[1].startsWith("<")) {
                return member[1];
            }
        }
        return null;
    }

    synchronized @Nullable ConstantPools open() {
        if (!this.asked) {
            this.asked = true;
            this.pools = ConstantPools.open(this.instrumentation);
        }
        return this.pools;
    }
}
