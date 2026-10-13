package dev.moon.hotwire.mixin_copy.copy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.injection.invoke.arg.ArgsClassGenerator;
import org.spongepowered.asm.mixin.throwables.MixinError;
import org.spongepowered.asm.mixin.throwables.MixinException;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

public final class CopyMixer implements Function<Object[], Object[]> {
    private static final String ARGS_PREFIX = "org/spongepowered/asm/synthetic/args/Args$";
    private static final byte[] ARGS_BYTES = ARGS_PREFIX.getBytes(StandardCharsets.UTF_8);

    public static final List<String> EXTENSIONS = List.of("com.llamalad7.mixinextras.MixinExtrasBootstrap",
            "com.bawnorton.mixinsquared.MixinSquaredBootstrap",

            "gg.essential.lib.mixinextras.MixinExtrasBootstrap");

    private boolean booted;

    @Override
    @SuppressWarnings("unchecked")

    public Object[] apply(Object[] request) {
        List<String> targets = (List<String>) request[0];
        List<String> configs = (List<String>) request[3];
        String sessionId = (String) request[4];
        Map<String, String> modIds = (Map<String, String>) request[10];
        Map<String, Integer> levels = (Map<String, Integer>) request[11];
        CopyMixinService.startMix((Map<String, byte[]>) request[1], (Map<String, byte[]>) request[2],
                (ClassLoader) request[5], (List<String>) request[6], (Function<String, byte @Nullable []>) request[7],
                (BiConsumer<String, String>) request[8], (String) request[9], (String) request[12]);
        String compatibility = (String) request[13];
        BooleanSupplier stop = (BooleanSupplier) request[14];
        Map<String, Integer> orders = (Map<String, Integer>) request[15];

        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        thread.setContextClassLoader(CopyMixinService.MODS);
        try {
            return this.mixAll(targets, configs, sessionId, modIds, levels, compatibility, stop, orders);
        } finally {
            thread.setContextClassLoader(context);
            CopyMixinService.endMix();
        }
    }

    private Object[] mixAll(List<String> targets, List<String> configs, String sessionId, Map<String, String> modIds,
            Map<String, Integer> levels, @Nullable String compatibility, BooleanSupplier stop,
            Map<String, Integer> orders) {
        long[] stages = new long[4];
        long start = System.nanoTime();
        boolean first = !this.booted;
        if (first) {
            MixinBootstrap.init();
            setSide(CopyMixinService.getSide());
            String obfuscation = CopyMixinService.getObfuscationContext();
            if (obfuscation != null) {
                MixinEnvironment.getDefaultEnvironment().setObfuscationContext(obfuscation);
            }
            for (String extension : EXTENSIONS) {
                bootExtension(extension);
            }
            this.booted = true;
        }

        setCompatibility(compatibility == null ? getHighestLevel()
                : MixinEnvironment.CompatibilityLevel.valueOf(compatibility));
        long booted = System.nanoTime();
        addConfigs(configs);
        decorate(modIds, levels);
        long added = System.nanoTime();
        IMixinTransformer transformer = prepare(sessionId, first);
        orderAsLaunched(transformer, orders);
        long prepared = System.nanoTime();
        Map<String, byte[]> mixed = new LinkedHashMap<>();
        for (String target : targets) {
            if (stop.getAsBoolean()) {
                break;
            }
            mixed.put(target, mix(transformer, target));
        }
        stages[0] = booted - start;
        stages[1] = added - booted;
        stages[2] = prepared - added;
        stages[3] = System.nanoTime() - prepared;
        return new Object[] {mixed, stages, collectArgsShapes(transformer, mixed),
                collectGenerated(transformer, mixed)};
    }

    private static Map<String, byte[]> collectGenerated(IMixinTransformer transformer, Map<String, byte[]> mixed) {
        Map<String, byte[]> made = new LinkedHashMap<>();
        try {
            Object extensions = transformer.getExtensions();
            Field generators = extensions.getClass().getDeclaredField("generators");
            generators.setAccessible(true);
            for (Object generator : (List<?>) generators.get(extensions)) {
                if (!generator.getClass().getName().endsWith(".InnerClassGenerator")) {
                    continue;
                }
                Field inner = generator.getClass().getDeclaredField("innerClasses");
                inner.setAccessible(true);
                for (Object key : ((Map<?, ?>) inner.get(generator)).keySet()) {
                    String name = String.valueOf(key).replace('.', '/');
                    if (isBesideAny(name, mixed.keySet())) {
                        byte[] bytes = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(),
                                name.replace('/', '.'));
                        if (bytes != null) {
                            made.put(name, bytes);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mixin's copy would not say which inner classes it generated: " + e, e);
        }
        return made;
    }

    private static boolean isBesideAny(String name, Set<String> targets) {
        for (int at = name.indexOf('$'); at > 0; at = name.indexOf('$', at + 1)) {
            if (targets.contains(name.substring(0, at))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> collectArgsShapes(IMixinTransformer transformer, Map<String, byte[]> mixed) {
        Set<String> referenced = new TreeSet<>();
        for (byte[] bytes : mixed.values()) {
            if (!contains(bytes, ARGS_BYTES)) {
                continue;
            }
            new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
                @Override
                public String map(String name) {
                    if (name.startsWith(ARGS_PREFIX)) {
                        referenced.add(name);
                    }
                    return name;
                }
            }), 0);
        }
        Map<String, String> shapes = new LinkedHashMap<>();
        if (referenced.isEmpty()) {
            return shapes;
        }
        try {
            Object extensions = transformer.getExtensions();
            Field generators = extensions.getClass().getDeclaredField("generators");
            generators.setAccessible(true);
            for (Object generator : (List<?>) generators.get(extensions)) {
                if (!(generator instanceof ArgsClassGenerator args)) {
                    continue;
                }
                Field byName = ArgsClassGenerator.class.getDeclaredField("nameToClass");
                byName.setAccessible(true);
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) byName.get(args)).entrySet()) {
                    String name = String.valueOf(entry.getKey()).replace('.', '/');
                    if (referenced.contains(name)) {
                        Field desc = entry.getValue().getClass().getDeclaredField("desc");
                        desc.setAccessible(true);
                        shapes.put(name, String.valueOf(desc.get(entry.getValue())));
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mixin's copy would not say its Args classes' shapes: " + e, e);
        }
        return shapes;
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        for (int at = 0; at <= bytes.length - part.length; at++) {
            if (bytes[at] == part[0] && Arrays.equals(bytes, at, at + part.length, part, 0, part.length)) {
                return true;
            }
        }
        return false;
    }

    private static void addConfigs(List<String> configs) {
        for (String config : configs) {
            try {
                Mixins.addConfiguration(config);
            } catch (MixinError | MixinException e) {
                throw new IllegalStateException("Mixin could not read the config " + config + ": " + e.getMessage(), e);
            }
        }
    }

    private static void decorate(Map<String, String> modIds, Map<String, Integer> levels) {
        for (Config config : Mixins.getConfigs()) {
            String mod = modIds.get(config.getName());
            Integer level = levels.get(config.getName());
            if (mod != null) {
                config.getConfig().decorate(FabricUtil.KEY_MOD_ID, mod);
            }
            if (level != null) {
                config.getConfig().decorate(FabricUtil.KEY_COMPATIBILITY, level);
            }
        }
    }

    private static IMixinTransformer prepare(String sessionId, boolean first) {
        try {
            return enterDefault(sessionId, first);
        } catch (MixinError | MixinException e) {
            throw new IllegalStateException("Mixin's copy could not start its configs: " + e.getMessage(), e);
        }
    }

    private static void orderAsLaunched(IMixinTransformer transformer, Map<String, Integer> orders) {
        if (orders.isEmpty()) {
            return;
        }
        CopyMixinInternals.Order bound = CopyMixinInternals.order();
        int after = 1;
        for (int order : orders.values()) {
            after = Math.max(after, order + 1);
        }
        try {
            Object processor = CopyMixinInternals.start().processor().get(transformer);
            bound.checkSelect().invoke(processor, MixinEnvironment.getCurrentEnvironment());
            for (Object config : (List<?>) bound.configs().get(processor)) {
                for (Object mixin : (List<?>) bound.mixins().get(config)) {
                    Integer launched = orders.get((String) bound.className().get(mixin));
                    bound.order().setInt(mixin, launched != null ? launched : after + bound.order().getInt(mixin));
                }
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof MixinError || e.getCause() instanceof MixinException) {
                throw new IllegalStateException("Mixin's copy could not start its configs: "
                        + e.getCause().getMessage(), e.getCause());
            }
            throw new IllegalStateException("Mixin's copy could not choose its configs: " + e.getCause(), e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mixin's copy could not order its mixins: " + e, e);
        }
    }

    private static IMixinTransformer enterDefault(String sessionId, boolean first) {
        CopyMixinInternals.Start bound = CopyMixinInternals.start();
        try {
            if (first) {
                bound.gotoPhase().invoke(null, MixinEnvironment.Phase.INIT);
                bound.gotoPhase().invoke(null, MixinEnvironment.Phase.DEFAULT);
            }
            IMixinTransformer transformer = CopyMixinService.getTransformer();
            if (transformer == null) {
                throw new IllegalStateException("Mixin's copy made no transformer");
            }
            Object processor = bound.processor().get(transformer);
            bound.sessionId().set(processor, sessionId);
            for (Object coprocessor : (List<?>) bound.coprocessors().get(processor)) {
                if (bound.accessorSessionId().getDeclaringClass().isInstance(coprocessor)) {
                    bound.accessorSessionId().set(coprocessor, sessionId);
                }
            }
            return transformer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mixin's copy could not be started: " + e, e);
        }
    }

    private static void setSide(String side) {
        try {
            CopyMixinInternals.start().sideName().set(MixinService.getService(), side);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mixin's copy could not be told its side: " + e, e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void setCompatibility(MixinEnvironment.CompatibilityLevel level) {
        MixinEnvironment.setCompatibilityLevel(level);
    }

    private static MixinEnvironment.CompatibilityLevel getHighestLevel() {
        int running = 44 + Runtime.version().feature();
        MixinEnvironment.CompatibilityLevel best = MixinEnvironment.CompatibilityLevel.JAVA_8;
        for (MixinEnvironment.CompatibilityLevel level : MixinEnvironment.CompatibilityLevel.values()) {
            if (level.getClassVersion() <= running && level.getClassVersion() > best.getClassVersion()) {
                best = level;
            }
        }
        return best;
    }

    private static void bootExtension(String bootstrap) {
        try {
            Class.forName(bootstrap, true, CopyMixer.class.getClassLoader()).getMethod("init").invoke(null);
        } catch (ClassNotFoundException absent) {
            return;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(bootstrap + " could not be started in Mixin's copy: " + e, e);
        }
    }

    private static byte[] mix(IMixinTransformer transformer, String target) {
        byte[] original = CopyMixinService.findBytesOf(target);
        if (original == null) {
            throw new IllegalStateException("no original bytes were given for " + target);
        }
        String dotted = target.replace('/', '.');
        try {
            return transformer.transformClassBytes(dotted, dotted, original);
        } catch (MixinError | MixinException e) {
            throw new IllegalStateException("Mixin could not patch " + dotted + ": " + e.getMessage(), e);
        }
    }
}
