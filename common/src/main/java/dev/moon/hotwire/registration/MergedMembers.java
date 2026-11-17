package dev.moon.hotwire.registration;

import dev.moon.hotwire.redefine.HandlerGuards;
import dev.moon.hotwire.redefine.Linker;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class MergedMembers {
    private static final Pattern MERGED_LAMBDA = Pattern.compile("^(?:md|fd)[0-9a-f]{6}[$]([^$]+)[$]");
    private static final String MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";

    private MergedMembers() {
    }

    public static @Nullable String findMergedMod(Method method) {
        String mixin = findMixinOf(method);
        if (mixin != null) {
            return Linker.findMixinOwner(mixin.replace('.', '/'));
        }
        return findMergedModByName(method.getName());
    }

    public static @Nullable String findMergedModByName(String name) {
        Matcher lambda = MERGED_LAMBDA.matcher(name);
        return lambda.find() ? lambda.group(1) : HandlerGuards.findModInHandlerName(name);
    }

    public static @Nullable String findMergedMod(Field field) {
        String mixin = findMixinOf(field);
        return mixin == null ? null : Linker.findMixinOwner(mixin.replace('.', '/'));
    }

    public static @Nullable String findModOfMixin(String mixin) {
        return Linker.findMixinOwner(mixin.replace('.', '/'));
    }

    public static @Nullable String findMixinOf(AnnotatedElement member) {
        for (Annotation annotation : member.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(MIXIN_MERGED)) {
                try {
                    return String.valueOf(annotation.annotationType().getMethod("mixin").invoke(annotation));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public static @Nullable String findMixinIn(Class<?> type, String method, String mod) {
        Method[] methods;
        try {
            methods = type.getDeclaredMethods();
        } catch (LinkageError e) {
            return null;
        }
        for (Method declared : methods) {
            String mixin = declared.getName().equals(method) ? findMixinOf(declared) : null;
            if (mixin != null && mod.equals(findModOfMixin(mixin))) {
                return mixin;
            }
        }
        return null;
    }
}
