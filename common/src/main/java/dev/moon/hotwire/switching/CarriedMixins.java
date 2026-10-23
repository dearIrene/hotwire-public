package dev.moon.hotwire.switching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CarriedMixins {
    private static final Logger LOGGER = LoggerFactory.getLogger(CarriedMixins.class);
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String INJECTION = "Lorg/spongepowered/asm/mixin/injection/";
    private static final String MIXIN_EXTRAS = "Lcom/llamalad7/mixinextras/injector/";

    private final Map<String, ModMixins> mods;
    private final SwitchApplier.Game game;

    CarriedMixins(Map<String, ModMixins> mods, SwitchApplier.Game game) {
        this.mods = mods;
        this.game = game;
    }

    List<String> findLost(Map<String, Set<String>> carriedNow, Map<String, Set<String>> carried,
            Map<String, byte[]> mixed, Set<String> on, Predicate<String> askedAgain) {
        Set<String> offMixins = this.getMixinsOf(id -> !on.contains(id));
        List<String> found = new ArrayList<>();
        new TreeMap<>(carriedNow).forEach((type, mixins) -> new TreeSet<>(mixins).forEach(mixin -> {
            if (carried.getOrDefault(type, Set.of()).contains(mixin) || isStillImplemented(mixed.get(type), mixin)) {
                return;
            }
            if (offMixins.contains(mixin)) {
                LOGGER.debug("[Hotwire] {} drops {}, whose mod is off", type, mixin);
            } else if (askedAgain.test(mixin)) {
                LOGGER.info("[Hotwire] {} drops {}, which its own plugin declined with the mods on now", type, mixin);
            } else if (this.game.findLoaded(mixin.replace('.', '/')) != null) {
                LOGGER.info("[Hotwire] {} drops {}, a loaded class and so not a mixin", type, mixin);
            } else if (isGaining(type, carriedNow, carried) && this.isSelfContained(mixin)) {
                LOGGER.info("[Hotwire] {} drops {}, which a mod coming on takes the place of", type, mixin);
            } else {
                found.add(type + " <- " + mixin);
            }
        }));
        return found;
    }

    private static boolean isStillImplemented(byte @Nullable [] mixed, String name) {
        return mixed != null && Arrays.asList(new ClassReader(mixed).getInterfaces()).contains(name.replace('.', '/'));
    }

    private static boolean isGaining(String type, Map<String, Set<String>> carriedNow,
            Map<String, Set<String>> carried) {
        for (String at = type; ; at = at.substring(0, at.lastIndexOf('$'))) {
            Set<String> gained = new HashSet<>(carried.getOrDefault(at, Set.of()));
            gained.removeAll(carriedNow.getOrDefault(at, Set.of()));
            if (!gained.isEmpty()) {
                return true;
            }
            if (at.lastIndexOf('$') <= at.lastIndexOf('/')) {
                return false;
            }
        }
    }

    private boolean isSelfContained(String mixin) {
        byte[] bytes = this.game.findOriginalBytes(mixin.replace('.', '/'));
        if (bytes == null) {
            return false;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!node.interfaces.isEmpty()) {
            return false;
        }
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_PRIVATE) == 0
                    && !isMarked(field.visibleAnnotations, field.invisibleAnnotations, SHADOW)) {
                return false;
            }
        }
        for (MethodNode method : node.methods) {
            boolean own = (method.access & Opcodes.ACC_PRIVATE) != 0 || method.name.equals("<init>")
                    || method.name.equals("<clinit>");
            if (!own && !isMarked(method.visibleAnnotations, method.invisibleAnnotations, OVERWRITE)
                    && !isMarked(method.visibleAnnotations, method.invisibleAnnotations, SHADOW)
                    && !isInjector(method)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInjector(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations,
                method.invisibleAnnotations)) {
            if (annotations != null && annotations.stream().anyMatch(annotation -> annotation.desc.startsWith(INJECTION)
                    || annotation.desc.startsWith(MIXIN_EXTRAS))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarked(@Nullable List<AnnotationNode> visible, @Nullable List<AnnotationNode> invisible,
            String desc) {
        return (visible != null && visible.stream().anyMatch(annotation -> annotation.desc.equals(desc)))
                || (invisible != null && invisible.stream().anyMatch(annotation -> annotation.desc.equals(desc)));
    }

    Set<String> getMixinsOf(Predicate<String> ids) {
        Set<String> names = new HashSet<>();
        this.mods.forEach((id, mixins) -> {
            if (ids.test(id)) {
                mixins.owns().forEach(name -> names.add(name.replace('/', '.')));
            }
        });
        return names;
    }

    List<String> findLeftBehind(Map<String, Set<String>> carried, Set<String> on) {
        Set<String> offMixins = this.getMixinsOf(id -> !on.contains(id));
        List<String> found = new ArrayList<>();
        new TreeMap<>(carried).forEach((type, mixins) -> new TreeSet<>(mixins).stream().filter(offMixins::contains)
                .forEach(mixin -> found.add(type + " <- " + mixin)));
        return found;
    }

    public @Nullable String findModShipping(String internalName) {
        try {
            for (Map.Entry<String, ModMixins> mod : this.mods.entrySet()) {
                if (mod.getValue().owns().contains(internalName)) {
                    return mod.getKey();
                }
            }
        } catch (ConcurrentModificationException e) {
            return null;
        }
        return null;
    }
}
