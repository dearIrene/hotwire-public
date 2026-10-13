package dev.moon.hotwire.mixin_copy.copy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

public final class CopyMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
    static final String NAME = "HotwireCopy";

    private static Map<String, byte[]> classes = Map.of();
    private static Function<String, byte @Nullable []> more = name -> null;
    private static Map<String, byte[]> resources = Map.of();
    private static List<String> shared = List.of();
    private static ClassLoader live = ClassLoader.getPlatformClassLoader();
    private static @Nullable BiConsumer<String, String> log;
    private static String side = "CLIENT";
    private static @Nullable String obfuscationContext;
    private static @Nullable IMixinTransformer transformer;

    private static @Nullable IMixinTransformerFactory factory;

    private static final List<String> OWN = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.spongepowered.", "org.objectweb.asm.", "com.google.", "com.llamalad7.mixinextras.",
            "com.bawnorton.mixinsquared.", "gg.essential.lib.mixinextras.", "dev.moon.hotwire.mixin_copy.");

    private static boolean isCopyOwn(String name) {
        for (String prefix : OWN) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static final ClassLoader MODS = new ClassLoader(CopyMixinService.class.getClassLoader()) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this.getClassLoadingLock(name)) {
                for (String prefix : shared) {
                    if (name.startsWith(prefix)) {
                        return live.loadClass(name);
                    }
                }
                Class<?> loaded = this.findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }

                if (!isCopyOwn(name)) {
                    byte[] bytes = findBytesOf(name.replace('.', '/'));
                    if (bytes != null) {
                        return this.defineClass(name, bytes, 0, bytes.length);
                    }
                }

                try {
                    return super.loadClass(name, resolve);
                } catch (ClassNotFoundException absent) {
                    byte[] bytes = findBytesOf(name.replace('.', '/'));
                    if (bytes == null) {
                        throw absent;
                    }
                    return this.defineClass(name, bytes, 0, bytes.length);
                }
            }
        }

        @Override
        protected @Nullable URL findResource(String name) {
            return live.getResource(name);
        }

        @Override
        protected Enumeration<URL> findResources(String name) throws IOException {
            return live.getResources(name);
        }
    };

    static void startMix(Map<String, byte[]> classes, Map<String, byte[]> resources, ClassLoader live,
            List<String> shared, Function<String, byte @Nullable []> more, @Nullable BiConsumer<String, String> log,
            String side, @Nullable String obfuscationContext) {
        CopyMixinService.classes = classes;
        CopyMixinService.resources = resources;
        CopyMixinService.live = live;
        CopyMixinService.shared = shared;
        CopyMixinService.more = more;
        CopyMixinService.log = log;
        CopyMixinService.side = side;
        CopyMixinService.obfuscationContext = obfuscationContext;
    }

    static void endMix() {
        classes = Map.of();
        resources = Map.of();
    }

    static @Nullable BiConsumer<String, String> getLog() {
        return log;
    }

    static String getSide() {
        return side;
    }

    static @Nullable String getObfuscationContext() {
        return obfuscationContext;
    }

    static @Nullable IMixinTransformer getTransformer() {
        return transformer;
    }

    static @Nullable IMixinTransformerFactory getFactory() {
        return factory;
    }

    static void setTransformer(IMixinTransformer made) {
        transformer = made;
    }

    @Override
    protected ILogger createLogger(String name) {
        return new CopyLogger(name);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory offered) {
            IMixinTransformer made = offered.createTransformer();
            transformer = made;
            factory = offered;

            MixinEnvironment.getCurrentEnvironment().setActiveTransformer(made);
        }
        super.offer(internal);
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_25;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    @Override
    public @Nullable IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return IFeatureValidator.ALLOW_ALL;
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return (required, requiredString) -> "Hotwire's copy of Mixin cannot run a config that needs " + requiredString;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual(NAME);
    }

    @Override

    public @Nullable InputStream getResourceAsStream(String name) {
        byte[] bytes = resources.get(name);
        return bytes == null ? live.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
    }

    @Override
    @Deprecated
    @SuppressWarnings({"deprecation", "InlineMeSuggester"})
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return MODS.loadClass(name);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return this.findClass(name);
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, CopyMixinService.class.getClassLoader());
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException {
        return this.getClassNode(name, true);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException {
        return this.getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException {
        String internal = name.replace('.', '/');
        byte[] bytes = findBytesOf(internal);
        if (bytes == null) {
            bytes = fromLoader(internal);
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, readerFlags);
        return node;
    }

    static byte @Nullable [] findBytesOf(String internal) {
        byte[] given = classes.get(internal);
        return given != null ? given : more.apply(internal);
    }

    private static byte[] fromLoader(String internal) throws ClassNotFoundException {
        try (InputStream in = CopyMixinService.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            if (in == null) {
                throw new ClassNotFoundException(internal.replace('/', '.'));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(internal.replace('/', '.'), e);
        }
    }

    @Override
    public Collection<ITransformer> getTransformers() {
        return List.of();
    }

    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return List.of();
    }

    @Override
    public void addTransformerExclusion(String name) {
    }

    @Override
    public void registerInvalidClass(String className) {
    }

    @Override
    public boolean isClassLoaded(String className) {
        return false;
    }

    @Override
    public String getClassRestrictions(String className) {
        return "";
    }
}
