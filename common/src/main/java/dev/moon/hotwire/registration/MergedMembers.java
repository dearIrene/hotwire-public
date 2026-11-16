package dev.moon.hotwire.registration;

import dev.moon.hotwire.redefine.HandlerGuards;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class MergedMembers {
    private static final Pattern MERGED_LAMBDA = Pattern.compile("^(?:md|fd)[0-9a-f]{6}[$]([^$]+)[$]");
    private static final String MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";

    private static volatile Function<String, @Nullable String> mixinOwner = name -> null;

    private MergedMembers() {
    }

    public static void setMixinOwners(Function<String, @Nullable String> owner) {
        mixinOwner = owner;
    }

    public static @Nullable String findMergedMod(Method method) {
        String mixin = findMixinOf(method);
        if (mixin != null) {
            return mixinOwner.apply(mixin.replace('.', '/'));
        }
        return findMergedModByName(method.getName());
    }

    public static @Nullable String findMergedModByName(String name) {
        Matcher lambda = MERGED_LAMBDA.matcher(name);
        return lambda.find() ? lambda.group(1) : HandlerGuards.findModInHandlerName(name);
    }

    public static @Nullable String findMergedMod(Field field) {
        String mixin = findMixinOf(field);
        return mixin == null ? null : mixinOwner.apply(mixin.replace('.', '/'));
    }

    public static @Nullable String findModOfMixin(String mixin) {
        return mixinOwner.apply(mixin.replace('.', '/'));
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
}
