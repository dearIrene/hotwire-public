package dev.moon.hotwire.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

public final class ClassPathIndex implements Function<String, @Nullable URL>, AutoCloseable {
    private static final String CLASS = ".class";
    private static final String META = "META-INF/";
    private static final int JAR_BITS = 16;

    private static final long HASH_MASK = (1L << 47) - 1;

    private static volatile @Nullable ClassPathIndex active;

    private static final List<Consumer<Path>> LEAVING = new CopyOnWriteArrayList<>();

    private final long[] keys;
    private final @Nullable OpenJar[] jars;
    private final List<String> samples;

    private record OpenJar(Path path, @Nullable JarFile file, String base, boolean own) {
        JarFile open() {
            return Objects.requireNonNull(this.file);
        }
    }

    private ClassPathIndex(long[] keys, @Nullable OpenJar[] jars, List<String> samples) {
        this.keys = keys;
        this.jars = jars;
        this.samples = samples;
    }

    public static ClassPathIndex of(URL[] path) {
        List<OpenJar> listed = new ArrayList<>();
        for (URL url : path) {
            OpenJar jar = listed.size() < (1 << JAR_BITS) ? open(url) : null;
            if (jar == null) {
                break;
            }
            listed.add(jar);
            if (namesMore(jar, url)) {
                break;
            }
        }
        long[][] found = IntStream.range(0, listed.size()).parallel()
                .mapToObj(number -> list(listed.get(number).open(), number)).toArray(long[][]::new);
        long[] keys = Arrays.stream(found).flatMapToLong(Arrays::stream).toArray();
        Arrays.parallelSort(keys);
        List<String> samples = new ArrayList<>();
        for (OpenJar jar : listed) {
            jar.open().versionedStream().map(ZipEntry::getName).filter(ClassPathIndex::isAnswered).findFirst()
                    .ifPresent(samples::add);
        }
        return new ClassPathIndex(keys, listed.toArray(OpenJar[]::new), samples);
    }

    private static @Nullable OpenJar open(URL url) {
        if (!url.getProtocol().equals("file")) {
            return null;
        }
        try {
            Path path = Path.of(url.toURI());
            if (!Files.isRegularFile(path)) {
                return null;
            }

            return new OpenJar(path.toAbsolutePath().normalize(), new JarFile(path.toFile(), false, ZipFile.OPEN_READ,
                    JarFile.runtimeVersion()), "jar:" + url + "!/", true);
        } catch (URISyntaxException | IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean namesMore(OpenJar jar, URL url) {
        try {
            ZipEntry entry = jar.open().getEntry(JarFile.MANIFEST_NAME);
            if (entry == null) {
                return false;
            }
            try (InputStream in = jar.open().getInputStream(entry)) {
                if (!new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT)
                        .contains("class-path")) {
                    return false;
                }
            }
            Manifest manifest = jar.open().getManifest();
            String named = manifest == null ? null : manifest.getMainAttributes().getValue("Class-Path");
            if (named == null) {
                return false;
            }
            for (String one : Pattern.compile("\\s+").splitAsStream(named.trim()).toList()) {
                URI more = url.toURI().resolve(one);
                if (!"file".equals(more.getScheme()) || Files.exists(Path.of(more))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return true;
        }
    }

    private static long[] list(JarFile jar, int number) {
        return jar.versionedStream().map(ZipEntry::getName)

                .map(name -> name.endsWith("/") ? name.substring(0, name.length() - 1) : name)
                .filter(ClassPathIndex::isAnswered).mapToLong(name -> (hash(name) << JAR_BITS) | number).toArray();
    }

    private static boolean isAnswered(String name) {
        if (!name.endsWith(CLASS) || name.startsWith(META)) {
            return false;
        }
        for (int at = 0; at < name.length(); at++) {
            char letter = name.charAt(at);
            if ((letter < 'a' || letter > 'z') && (letter < 'A' || letter > 'Z') && (letter < '0' || letter > '9')
                    && letter != '/' && letter != '$' && letter != '_' && letter != '.' && letter != '-') {
                return false;
            }
        }
        return true;
    }

    private static long hash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (int at = 0; at < name.length(); at++) {
            hash = (hash ^ name.charAt(at)) * 0x100000001b3L;
        }
        return (hash ^ (hash >>> 29)) & HASH_MASK;
    }

    @Override
    public @Nullable URL apply(@Nullable String name) {
        if (name == null || !isAnswered(name)) {
            return null;
        }
        int at = this.find(hash(name));
        if (at < 0) {
            return null;
        }
        OpenJar jar = this.jars[(int) (this.keys[at] & ((1 << JAR_BITS) - 1))];
        try {
            JarFile file = jar == null ? null : jar.file();
            JarEntry entry = file == null ? null : file.getJarEntry(name);
            if (jar == null || file == null || entry == null) {
                return null;
            }

            boolean versioned = file.isMultiRelease();
            return versioned && entry.isDirectory() ? null
                    : URI.create(jar.base() + (versioned ? entry.getRealName() : name)).toURL();
        } catch (RuntimeException | MalformedURLException e) {
            return null;
        }
    }

    private int find(long hash) {
        int low = 0;
        int high = this.keys.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (this.keys[middle] >>> JAR_BITS < hash) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low < this.keys.length && this.keys[low] >>> JAR_BITS == hash ? low : -1;
    }

    public boolean isAbsent(@Nullable String name) {
        return name != null && isAnswered(name) && this.find(hash(name)) < 0;
    }

    public static @Nullable Path findJarWithSame(String name, byte[] bytes) {
        ClassPathIndex index = active;
        if (index == null || !isAnswered(name)) {
            return null;
        }
        int at = index.find(hash(name));
        OpenJar jar = at < 0 ? null : index.jars[(int) (index.keys[at] & ((1 << JAR_BITS) - 1))];
        try {
            JarFile file = jar == null ? null : jar.file();
            JarEntry entry = file == null ? null : file.getJarEntry(name);
            if (jar == null || entry == null || entry.getSize() != bytes.length) {
                return null;
            }
            CRC32 sum = new CRC32();
            sum.update(bytes);
            return sum.getValue() == entry.getCrc() ? jar.path() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static byte @Nullable [] read(String name, Path from) {
        ClassPathIndex index = active;
        if (index == null) {
            return null;
        }
        int at = index.find(hash(name));
        OpenJar jar = at < 0 ? null : index.jars[(int) (index.keys[at] & ((1 << JAR_BITS) - 1))];
        JarFile file = jar == null ? null : jar.file();
        if (jar == null || file == null || !jar.path().equals(from)) {
            return null;
        }
        try {
            JarEntry entry = file.getJarEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void onLeaving(Consumer<Path> listener) {
        LEAVING.add(listener);
    }

    public static void drop(Path jar) {
        ClassPathIndex index = active;
        if (index == null) {
            return;
        }
        Path wanted = jar.toAbsolutePath().normalize();
        LEAVING.forEach(listener -> listener.accept(wanted));
        synchronized (index) {
            for (int number = 0; number < index.jars.length; number++) {
                OpenJar listed = index.jars[number];
                if (listed != null && listed.path().equals(wanted)) {
                    index.jars[number] = null;
                    closeOwn(listed);
                }
            }
        }
    }

    private static void closeOwn(OpenJar jar) {
        JarFile file = jar.file();
        if (jar.own() && file != null) {
            try {
                file.close();
            } catch (IOException e) {
            }
        }
    }

    public synchronized void useFiles(Function<Path, @Nullable JarFile> loaders) {
        for (int number = 0; number < this.jars.length; number++) {
            OpenJar jar = this.jars[number];
            if (jar != null && jar.own()) {
                this.jars[number] = new OpenJar(jar.path(), loaders.apply(jar.path()), jar.base(), false);
                closeOwn(jar);
            }
        }
    }

    @Override
    public synchronized void close() {
        for (int number = 0; number < this.jars.length; number++) {
            OpenJar jar = this.jars[number];
            this.jars[number] = null;
            if (jar != null) {
                closeOwn(jar);
            }
        }
    }

    public void activate() {
        active = this;
    }

    public int countJars() {
        return this.jars.length;
    }

    public int countNames() {
        return this.keys.length;
    }

    public List<String> getSamples() {
        return this.samples;
    }
}
