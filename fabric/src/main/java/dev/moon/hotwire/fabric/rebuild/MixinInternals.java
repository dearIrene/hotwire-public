package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.jvm.PrivateField;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.ArgsClassGenerator;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ISyntheticClassInfo;
import org.spongepowered.asm.service.MixinService;

public final class MixinInternals {
    private static final String TRANSFORMER = "org.spongepowered.asm.mixin.transformer.IMixinTransformer";

    private final String sessionId;
    private final List<String> configs;
    private final Object argsGenerator;
    private final IMixinInfo anyMixin;

    private final Object generated;

    private final Object processor;

    private final Map<String, String> modIdOf;
    private final Map<String, Integer> levelOf;

    private final Map<String, Integer> orderOf;

    private MixinInternals(String sessionId, List<String> configs, Map<String, String> modIdOf,
            Map<String, Integer> levelOf, Map<String, Integer> orderOf, Object argsGenerator, IMixinInfo anyMixin,
            Object generated, Object processor) {
        this.sessionId = sessionId;
        this.generated = generated;
        this.processor = processor;
        this.argsGenerator = argsGenerator;
        this.anyMixin = anyMixin;
        this.configs = List.copyOf(configs);
        this.modIdOf = Map.copyOf(modIdOf);
        this.levelOf = Map.copyOf(levelOf);
        this.orderOf = Map.copyOf(orderOf);
    }

    public static MixinInternals bind() {
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        if (transformer == null) {
            throw new IllegalStateException("[Hotwire] the game's Mixin has no active transformer");
        }
        Object mixins = unwrap(transformer);
        Object processor = PrivateField.of(mixins.getClass(), "processor").get(mixins, Object.class);
        String session = PrivateField.of(processor.getClass(), "sessionId").get(processor, String.class);
        List<String> names = new ArrayList<>();
        Map<String, String> modIdOf = new HashMap<>();
        Map<String, Integer> levelOf = new HashMap<>();
        Map<String, Integer> orderOf = new HashMap<>();
        List<?> configs = PrivateField.of(processor.getClass(), "configs").get(processor, List.class);
        for (Object config : configs) {
            for (Object mixin : PrivateField.of(Objects.requireNonNull(config).getClass(), "mixins")
                    .get(config, List.class)) {
                if (mixin instanceof IMixinInfo made) {
                    orderOf.put(made.getClassName(), PrivateField.of(mixin.getClass(), "order")
                            .get(mixin, Integer.class));
                }
            }
            if (config instanceof IMixinConfig mixinConfig) {
                names.add(mixinConfig.getName());
                if (mixinConfig.hasDecoration(FabricUtil.KEY_MOD_ID)) {
                    modIdOf.put(mixinConfig.getName(), mixinConfig.getDecoration(FabricUtil.KEY_MOD_ID));
                }
                if (mixinConfig.hasDecoration(FabricUtil.KEY_COMPATIBILITY)) {
                    levelOf.put(mixinConfig.getName(), mixinConfig.getDecoration(FabricUtil.KEY_COMPATIBILITY));
                }
            }
        }
        Object generated = PrivateField.of(mixins.getClass(), "syntheticClassRegistry").get(mixins, Object.class);
        return new MixinInternals(session, names, modIdOf, levelOf, orderOf, findArgsGenerator(transformer),
                findAnyMixin(configs), generated, processor);
    }

    private static Object findArgsGenerator(Object transformer) {
        try {
            Object extensions = PrivateField.of(transformer.getClass(), "extensions").get(transformer, Object.class);
            Field generators = extensions.getClass().getDeclaredField("generators");
            generators.setAccessible(true);
            for (Object generator : (List<?>) generators.get(extensions)) {
                if (generator instanceof ArgsClassGenerator) {
                    return generator;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[Hotwire] Mixin's generators cannot be read: " + e, e);
        }
        throw new IllegalStateException("[Hotwire] Mixin has no Args class generator");
    }

    private static IMixinInfo findAnyMixin(List<?> configs) {
        for (Object config : configs) {
            List<?> mixins = PrivateField.of(Objects.requireNonNull(config).getClass(), "mixins")
                    .get(config, List.class);
            if (!mixins.isEmpty() && mixins.getFirst() instanceof IMixinInfo mixin) {
                return mixin;
            }
        }
        throw new IllegalStateException("[Hotwire] the game's Mixin has no mixins");
    }

    public String getArgsClassFor(String shape) {
        return ((ArgsClassGenerator) this.argsGenerator).getArgsClass(shape, this.anyMixin).getName()
                .replace('.', '/');
    }

    public Object getProcessor() {
        return this.processor;
    }

    public List<Object> getConfigObjects() {
        List<?> all = PrivateField.of(this.processor.getClass(), "configs").get(this.processor, List.class);
        return new ArrayList<>(all);
    }

    @SuppressWarnings("ReferenceEquality")
    public byte @Nullable [] findMadeClass(String internal) {
        String name = internal.replace('/', '.');
        IMixinService own = MixinService.getService();
        for (Object config : this.getConfigObjects()) {
            if (!(config instanceof IMixinConfig made) || made.getMixinPackage().isEmpty()
                    || !name.startsWith(made.getMixinPackage())
                    || !(PrivateField.of(config.getClass(), "service").get(config, Object.class)
                    instanceof IMixinService given) || given == own) {
                continue;
            }
            try {
                ClassNode node = given.getBytecodeProvider().getClassNode(name, false);
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                return writer.toByteArray();
            } catch (ClassNotFoundException | IOException e) {
            }
        }
        return null;
    }

    public List<String> getGeneratedBy(String mod) {
        Map<?, ?> classes = PrivateField.of(this.generated.getClass(), "classes").get(this.generated, Map.class);
        List<?> all = List.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                all = new ArrayList<>(classes.values());
                break;
            } catch (ConcurrentModificationException e) {
            }
        }
        List<String> names = new ArrayList<>();
        for (Object info : all) {
            if (info instanceof ISyntheticClassInfo synthetic && synthetic.isLoaded() && synthetic.getMixin() != null
                    && mod.equals(this.modIdOf.get(synthetic.getMixin().getConfig().getName()))) {
                names.add(synthetic.getClassName());
            }
        }
        return names;
    }

    private static Object unwrap(Object transformer) {
        Object current = transformer;
        for (int depth = 0; depth < 4; depth++) {
            if (isMixins(current)) {
                return current;
            }
            Object inner = findInnerTransformer(current);
            if (inner == null) {
                break;
            }
            current = inner;
        }
        throw new IllegalStateException("[Hotwire] Mixin's transformer is not inside " + transformer.getClass().getName(
                ));
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isMixins(Object transformer) {
        return hasField(transformer.getClass(), "processor");
    }

    private static @Nullable Object findInnerTransformer(Object wrapper) {
        Field field = findInnerField(wrapper);
        return field == null ? null : PrivateField.of(field.getDeclaringClass(), field.getName()).get(wrapper,
                Object.class);
    }

    @SuppressWarnings("ReferenceEquality")
    static @Nullable Field findInnerField(Object wrapper) {
        for (Class<?> at = wrapper.getClass(); at != null; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && isTransformer(field.getType())) {
                    Object inner = PrivateField.of(at, field.getName()).get(wrapper, Object.class);
                    if (inner != wrapper) {
                        field.setAccessible(true);
                        return field;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isTransformer(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            if (at.getName().equals(TRANSFORMER)) {
                return true;
            }
            for (Class<?> face : at.getInterfaces()) {
                if (isTransformer(face)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable String findObfuscationContext() {
        return MixinEnvironment.getDefaultEnvironment().getObfuscationContext();
    }

    String getSessionId() {
        return this.sessionId;
    }

    MixinCopy.Decorations getDecorations() {
        return new MixinCopy.Decorations(this.modIdOf, this.levelOf, this.orderOf);
    }

    public List<String> getConfigs() {
        return this.configs;
    }
}
