package dev.moon.hotwire.jvm;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassPathListing {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClassPathListing.class);
    private static final String VERSIONED = "META-INF/versions/";
    private static final @Nullable Supplier<URL[]> SEARCH_PATH = findSearchPath();
    private static final Set<String> JDK = findJdkPackages();
    private static long @Nullable [] listed;
    private static int listedFor = -1;

    private ClassPathListing() {
    }

    public static boolean canHave(String file) {
        int slash = file.lastIndexOf('/');
        if (JDK.contains(slash < 0 ? "" : file.substring(0, slash))) {
            return true;
        }
        long[] known = findListed();
        return known == null || Arrays.binarySearch(known, hash(file)) >= 0;
    }

    static long hash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (int at = 0; at < name.length(); at++) {
            hash = (hash ^ name.charAt(at)) * 0x100000001b3L;
        }
        return hash;
    }

    private static synchronized long @Nullable [] findListed() {
        Supplier<URL[]> searchPath = SEARCH_PATH;
        if (searchPath == null) {
            return null;
        }
        URL[] path;
        try {
            path = searchPath.get();
        } catch (RuntimeException e) {
            return null;
        }
        if (path.length != listedFor) {
            Set<String> names = list(path);
            listed = names == null ? null : names.stream().mapToLong(ClassPathListing::hash).sorted().toArray();
            listedFor = path.length;
            LOGGER.debug("[Hotwire] the class path's listing: {}", names == null
                    ? "none, its loader answers for every class (" + path.length + " entries)"
                    : names.size() + " class files in " + path.length + " entries");
        }
        return listed;
    }

    static @Nullable Set<String> list(URL[] path) {
        Set<String> names = new HashSet<>();
        int runtime = Runtime.version().feature();
        for (URL url : path) {
            Path file;
            try {
                if (!"file".equals(url.getProtocol())) {
                    return null;
                }
                file = Path.of(url.toURI());
            } catch (URISyntaxException | IllegalArgumentException e) {
                return null;
            }
            if (!Files.isRegularFile(file)) {
                if (Files.exists(file)) {
                    return null;
                }
                continue;
            }
            try (JarFile jar = new JarFile(file.toFile())) {
                Manifest manifest = jar.getManifest();
                if (manifest != null && manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) != null) {
                    return null;
                }
                jar.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".class"))
                        .forEach(name -> addListed(names, name, runtime));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return names;
    }

    private static void addListed(Set<String> names, String name, int runtime) {
        if (!name.startsWith(VERSIONED)) {
            names.add(name);
            return;
        }
        int end = name.indexOf('/', VERSIONED.length());
        String version = end < 0 ? "" : name.substring(VERSIONED.length(), end);
        if (!version.isEmpty() && version.chars().allMatch(Character::isDigit)
                && Integer.parseInt(version) <= runtime) {
            names.add(name.substring(end + 1));
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Supplier<URL[]> findSearchPath() {
        try {
            MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();
            ClassLoader system = ClassLoader.getSystemClassLoader();
            Class<?> builtin = Class.forName("jdk.internal.loader.BuiltinClassLoader");
            Class<?> classPath = Class.forName("jdk.internal.loader.URLClassPath");
            if (!builtin.isInstance(system)) {
                return null;
            }
            MethodHandle ucp = trusted.findGetter(builtin, "ucp", classPath).bindTo(system);
            MethodHandle urls = trusted.findVirtual(classPath, "getURLs", MethodType.methodType(URL[].class));
            return MethodHandleProxies.asInterfaceInstance(Supplier.class, MethodHandles.filterReturnValue(ucp,
                    urls));
        } catch (JvmAccessException | ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("[Hotwire] the system loader's search path cannot be read, so it answers for every class: {}",
                    e.toString());
            return null;
        }
    }

    private static Set<String> findJdkPackages() {
        Set<String> packages = new HashSet<>();
        ModuleLayer.boot().modules().forEach(module -> module.getPackages()
                .forEach(name -> packages.add(name.replace('.', '/'))));
        return Set.copyOf(packages);
    }
}
