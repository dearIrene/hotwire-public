package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.jvm.PrivateField;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KnotInternals {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnotInternals.class);

    private static final int CHECKED_READS = 64;
    private final Function<String, byte @Nullable []> preMixin;
    private final Predicate<String> loaded;

    private volatile @Nullable PreMixinReads ownFileReads;
    private final AtomicInteger checkedReads = new AtomicInteger();

    private KnotInternals(Function<String, byte @Nullable []> preMixin, Predicate<String> loaded) {
        this.preMixin = preMixin;
        this.loaded = loaded;
        this.ownFileReads = PreMixinReads.bind();
    }

    @SuppressWarnings("unchecked")
    public static KnotInternals bind() {
        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        Object delegate = PrivateField.of(launcher.getClass(), "classLoader").get(launcher, Object.class);
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(delegate.getClass(), MethodHandles.lookup());
            MethodHandle bytes = lookup.findVirtual(delegate.getClass(), "getPreMixinClassBytes",
                    MethodType.methodType(byte[].class, String.class)).bindTo(delegate);
            MethodHandle isLoaded = lookup.findVirtual(delegate.getClass(), "isClassLoaded",
                    MethodType.methodType(boolean.class, String.class)).bindTo(delegate);
            return new KnotInternals(MethodHandleProxies.asInterfaceInstance(Function.class, bytes),
                    MethodHandleProxies.asInterfaceInstance(Predicate.class, isLoaded));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("[Hotwire] cannot reach Knot's class bytes in "
                    + delegate.getClass().getName() + ": " + e, e);
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public static void dropFromClassPath(Path jar) throws IOException {
        Path wanted = jar.toAbsolutePath().normalize();
        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        Object delegate = PrivateField.of(launcher.getClass(), "classLoader").get(launcher, Object.class);
        Object knot = PrivateField.of(delegate.getClass(), "classLoader").get(delegate, Object.class);
        URLClassLoader urls = PrivateField.of(knot.getClass(), "urlLoader").get(knot, URLClassLoader.class);
        try {
            MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();

            VarHandle sources = trusted.findVarHandle(delegate.getClass(), "codeSources", Set.class);
            synchronized (delegate) {
                Set<Path> left = new HashSet<>();
                for (Object source : (Set<?>) sources.get(delegate)) {
                    if (source instanceof Path known && !isSame(known, wanted)) {
                        left.add(known);
                    }
                }
                sources.set(delegate, left);
            }
            Class<?> pathType = Class.forName("jdk.internal.loader.URLClassPath");
            Class<?> loaderType = Class.forName("jdk.internal.loader.URLClassPath$Loader");
            Object classPath = trusted.findVarHandle(URLClassLoader.class, "ucp", pathType).get(urls);
            VarHandle path = trusted.findVarHandle(pathType, "path", ArrayList.class);
            VarHandle unopened = trusted.findVarHandle(pathType, "unopenedUrls", ArrayDeque.class);
            VarHandle loaders = trusted.findVarHandle(pathType, "loaders", ArrayList.class);
            VarHandle byName = trusted.findVarHandle(pathType, "lmap", HashMap.class);
            MethodHandle baseOf = trusted.findVirtual(loaderType, "getBaseURL", MethodType.methodType(URL.class));
            MethodHandle close = trusted.findVirtual(loaderType, "close", MethodType.methodType(void.class));
            int closed = 0;
            synchronized (classPath) {
                ((ArrayList<?>) path.get(classPath)).removeIf(url -> isAt((URL) url, wanted));
                ((ArrayDeque<?>) unopened.get(classPath)).removeIf(url -> isAt((URL) url, wanted));
                for (Iterator<?> opened = ((ArrayList<?>) loaders.get(classPath)).iterator(); opened.hasNext();) {
                    Object loader = opened.next();
                    if (isAt((URL) baseOf.invoke(loader), wanted)) {
                        opened.remove();
                        ((HashMap<?, ?>) byName.get(classPath)).values().remove(loader);
                        close.invoke(loader);
                        closed++;
                    }
                }
            }

            Class<?> factory = Class.forName("sun.net.www.protocol.jar.JarFileFactory");
            HashMap<?, ?> byUrl = (HashMap<?, ?>) trusted.findStaticVarHandle(factory, "fileCache", HashMap.class)
                    .get();
            HashMap<?, ?> urlOf = (HashMap<?, ?>) trusted.findStaticVarHandle(factory, "urlCache", HashMap.class).get();
            synchronized (factory) {
                for (Iterator<? extends Map.Entry<?, ?>> cached = byUrl.entrySet().iterator(); cached.hasNext();) {
                    Map.Entry<?, ?> entry = cached.next();
                    if (entry.getValue() instanceof JarFile held && wanted.equals(Path.of(held.getName())
                            .toAbsolutePath().normalize())) {
                        cached.remove();
                        urlOf.remove(held);
                        held.close();
                        closed++;
                    }
                }
            }
            LOGGER.info("[Hotwire] {} let go by {} holder(s) of Knot's", wanted.getFileName(), closed);
        } catch (JvmAccessException | ReflectiveOperationException e) {
            throw new IOException("Knot's class path is out of reach: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Knot's class path could not be changed: " + e, e);
        }
    }

    public static String describeOpenZips(Path jar) {
        Path wanted = jar.toAbsolutePath().normalize();
        try {
            MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();
            Class<?> sourceType = Class.forName(ZipFile.class.getName() + "$Source");
            Class<?> keyType = Class.forName(ZipFile.class.getName() + "$Source$Key");
            HashMap<?, ?> files = (HashMap<?, ?>) trusted.findStaticVarHandle(sourceType, "files", HashMap.class)
                    .get();
            VarHandle fileOf = trusted.findVarHandle(keyType, "file", File.class);
            StringBuilder out = new StringBuilder();
            synchronized (sourceType) {
                for (Object key : files.keySet()) {
                    File file = (File) fileOf.get(key);
                    if (file != null && wanted.equals(file.toPath().toAbsolutePath().normalize())) {
                        out.append("an open zip file of the JDK's holds it");
                    }
                }
            }
            return out.isEmpty() ? "no open zip file of the JDK's" : out.toString();
        } catch (JvmAccessException | ReflectiveOperationException | RuntimeException e) {
            return "the JDK's zip files could not be read: " + e;
        }
    }

    private static boolean isSame(Path one, Path other) {
        try {
            return Files.isSameFile(one, other);
        } catch (IOException e) {
            return one.toAbsolutePath().normalize().equals(other);
        }
    }

    private static boolean isAt(URL url, Path wanted) {
        try {
            String text = url.toString();
            if (text.startsWith("jar:") && text.endsWith("!/")) {
                text = text.substring("jar:".length(), text.length() - "!/".length());
            }
            return text.startsWith("file:") && Path.of(new URI(text)).toAbsolutePath().normalize()
                    .equals(wanted);
        } catch (URISyntaxException | RuntimeException notAFile) {
            return false;
        }
    }

    boolean isLoaded(String internal) {
        return this.loaded.test(internal.replace('/', '.'));
    }

    byte @Nullable [] findPreMixinBytes(String internal) {
        return this.preMixin.apply(internal.replace('/', '.'));
    }

    byte @Nullable [] findPreMixinBytes(String internal, @Nullable Class<?> loaded) {
        PreMixinReads reads = this.ownFileReads;
        byte[] own = reads == null || loaded == null ? null : reads.read(internal, loaded);
        if (own == null) {
            return this.findPreMixinBytes(internal);
        }
        if (this.checkedReads.getAndIncrement() < CHECKED_READS) {
            byte[] knot = this.findPreMixinBytes(internal);
            if (!Arrays.equals(own, knot)) {
                this.ownFileReads = null;
                LOGGER.warn("[Hotwire] {} read from its own file differs from Knot's read; Knot reads every class now",
                        internal);
                return knot;
            }
        }
        return own;
    }
}
