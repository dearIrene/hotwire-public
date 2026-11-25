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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassPathListing {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClassPathListing.class);
    private static final String VERSIONED = "META-INF/versions/";
    private static final @Nullable Supplier<URL[]> SEARCH_PATH = findSearchPath();
    private static final Set<String> JDK = findJdkPackages();
    private static @Nullable Listing listed;
    private static int listedFor = -1;

    private static @Nullable JarFile @Nullable [] opened;

    private static final class Listing {
        final long[] hashes;
        final int[] firstJar;
        final Path[] jars;

        Listing(long[] hashes, int[] firstJar, Path[] jars) {
            this.hashes = hashes;
            this.firstJar = firstJar;
            this.jars = jars;
        }
    }

    private ClassPathListing() {
    }

    public static boolean canHave(String file) {
        int slash = file.lastIndexOf('/');
        if (JDK.contains(slash < 0 ? "" : file.substring(0, slash))) {
            return true;
        }
        Listing known = findListed();
        return known == null || Arrays.binarySearch(known.hashes, hash(file)) >= 0;
    }

    public static @Nullable Path findFirstJar(String file) {
        Listing known = findListed();
        if (known == null) {
            return null;
        }
        long[] hashes = known.hashes;
        long hash = hash(file);
        int row = Arrays.binarySearch(hashes, hash);
        if (row < 0 || (row > 0 && hashes[row - 1] == hash) || (row + 1 < hashes.length && hashes[row + 1] == hash)) {
            return null;
        }
        int jar = known.firstJar[row];
        return hasEntry(known, jar, file) ? known.jars[jar] : null;
    }

    private static synchronized boolean hasEntry(Listing known, int at, String file) {
        @Nullable JarFile @Nullable [] was = opened;
        @Nullable JarFile[] open;
        if (was == null || was.length != known.jars.length) {
            open = new JarFile[known.jars.length];
            opened = open;
        } else {
            open = was;
        }
        try {
            JarFile jar = open[at];
            if (jar == null) {
                jar = new JarFile(known.jars[at].toFile(), true, ZipFile.OPEN_READ, Runtime.version());
                open[at] = jar;
            }
            return jar.getEntry(file) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static long hash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (int at = 0; at < name.length(); at++) {
            hash = (hash ^ name.charAt(at)) * 0x100000001b3L;
        }
        return hash;
    }

    private static synchronized @Nullable Listing findListed() {
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
            Map<String, Integer> names = listFirst(path);
            listed = names == null ? null : makeListing(names, path);
            listedFor = path.length;
            LOGGER.debug("[Hotwire] the class path's listing: {}", names == null
                    ? "none, its loader answers for every class (" + path.length + " entries)"
                    : names.size() + " class files in " + path.length + " entries");
        }
        return listed;
    }

    private static Listing makeListing(Map<String, Integer> names, URL[] path) {
        long[] hashes = new long[names.size()];
        int[] jars = new int[names.size()];
        Integer[] order = new Integer[names.size()];
        int at = 0;
        for (Map.Entry<String, Integer> name : names.entrySet()) {
            hashes[at] = hash(name.getKey());
            jars[at] = name.getValue();
            order[at] = at;
            at++;
        }
        Arrays.sort(order, (one, other) -> Long.compare(hashes[one], hashes[other]));
        long[] sorted = new long[order.length];
        int[] firstJar = new int[order.length];
        for (int row = 0; row < order.length; row++) {
            sorted[row] = hashes[order[row]];
            firstJar[row] = jars[order[row]];
        }
        Path[] files = new Path[path.length];
        for (int jar = 0; jar < path.length; jar++) {
            try {
                files[jar] = Path.of(path[jar].toURI());
            } catch (URISyntaxException | IllegalArgumentException e) {
                files[jar] = Path.of("");
            }
        }
        return new Listing(sorted, firstJar, files);
    }

    static @Nullable Set<String> list(URL[] path) {
        Map<String, Integer> names = listFirst(path);
        return names == null ? null : names.keySet();
    }

    static @Nullable Map<String, Integer> listFirst(URL[] path) {
        Map<String, Integer> names = new HashMap<>();
        int runtime = Runtime.version().feature();
        for (int at = 0; at < path.length; at++) {
            URL url = path[at];
            int place = at;
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
                        .forEach(name -> addListed(names, name, runtime, place));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return names;
    }

    private static void addListed(Map<String, Integer> names, String name, int runtime, int place) {
        if (!name.startsWith(VERSIONED)) {
            names.putIfAbsent(name, place);
            return;
        }
        int end = name.indexOf('/', VERSIONED.length());
        String version = end < 0 ? "" : name.substring(VERSIONED.length(), end);
        if (!version.isEmpty() && version.chars().allMatch(Character::isDigit)
                && Integer.parseInt(version) <= runtime) {
            names.putIfAbsent(name.substring(end + 1), place);
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
