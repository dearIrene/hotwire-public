package dev.moon.hotwire.mixin_copy.copy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;

public final class CopyMixinReset implements Runnable {
    private static final String OBJECT = "java/lang/Object";

    @Override
    public void run() {
        CopyMixinInternals.Reset bound = CopyMixinInternals.reset();
        IMixinTransformer old = CopyMixinService.getTransformer();
        IMixinTransformerFactory factory = CopyMixinService.getFactory();
        if (old == null || factory == null) {
            throw new IllegalStateException("Mixin's copy has not started, so there is nothing to reset");
        }
        try {
            Mixins.getConfigs().clear();
            clearEnvironmentConfigs();
            readCollection(bound.registeredConfigs()).clear();
            map(bound.allConfigs()).clear();
            readCollection(bound.globalMixinList()).clear();
            bound.configOrder().setInt(null, 0);
            bound.mixinOrder().setInt(null, 0);
            retainUntouched(map(bound.classInfoCache()), bound.classInfoMixins());
            readCollection(bound.mapperClasses()).clear();
            map(bound.mapperMethods()).clear();
            map(bound.localVariables()).clear();

            ((Map<?, ?>) bound.parentMixins().get(bound.inheritanceTracker().get(null))).clear();
            bound.activeTransformer().set(null, null);
            IMixinTransformer made = factory.createTransformer();
            MixinEnvironment.getCurrentEnvironment().setActiveTransformer(made);
            carryExtensions(bound.extensions(), old, made);
            CopyMixinService.setTransformer(made);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Mixin's copy could not be reset: " + e, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void retainUntouched(Map<?, ?> cache, Field mixinsOf) throws IllegalAccessException {
        Map<String, ClassInfo> infos = (Map<String, ClassInfo>) cache;
        Set<String> dropped = new HashSet<>();
        for (Map.Entry<String, ClassInfo> entry : infos.entrySet()) {
            ClassInfo info = entry.getValue();
            if (info == null || info.isMixin() || !((Set<?>) mixinsOf.get(info)).isEmpty()
                    || !info.getAppliedMixins().isEmpty()) {
                dropped.add(entry.getKey());
            }
        }
        for (boolean more = true; more;) {
            more = false;
            for (Map.Entry<String, ClassInfo> entry : infos.entrySet()) {
                ClassInfo info = entry.getValue();
                if (!dropped.contains(entry.getKey())
                        && (dropped.contains(info.getSuperName()) || dropped.contains(info.getOuterName()))) {
                    dropped.add(entry.getKey());
                    more = true;
                }
            }
        }
        dropped.remove(OBJECT);
        infos.keySet().removeAll(dropped);
    }

    @SuppressWarnings("deprecation")
    private static void clearEnvironmentConfigs() {
        MixinEnvironment.getDefaultEnvironment().getMixinConfigs().clear();
    }

    @SuppressWarnings("unchecked")
    private static void carryExtensions(Field field, IMixinTransformer old, IMixinTransformer made)
            throws IllegalAccessException {
        List<IExtension> before = (List<IExtension>) field.get(old.getExtensions());
        List<IExtension> after = (List<IExtension>) field.get(made.getExtensions());
        List<IExtension> fresh = new ArrayList<>(after);
        List<IExtension> ordered = new ArrayList<>(before.size());
        for (IExtension extension : before) {
            ordered.add(pickFreshOrSame(fresh, extension));
        }
        ordered.addAll(fresh);
        after.clear();
        after.addAll(ordered);
    }

    private static IExtension pickFreshOrSame(List<IExtension> fresh, IExtension extension) {
        for (int i = 0; i < fresh.size(); i++) {
            if (fresh.get(i).getClass() == extension.getClass()) {
                return fresh.remove(i);
            }
        }
        return extension;
    }

    private static Collection<?> readCollection(Field field) throws IllegalAccessException {
        return (Collection<?>) field.get(null);
    }

    private static Map<?, ?> map(Field field) throws IllegalAccessException {
        return (Map<?, ?>) field.get(null);
    }
}
