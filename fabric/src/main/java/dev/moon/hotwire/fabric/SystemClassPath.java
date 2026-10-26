package dev.moon.hotwire.fabric;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.jspecify.annotations.Nullable;

final class SystemClassPath {
    private static final String VERSIONED = "META-INF/versions/";

    private static final @Nullable Set<String> PACKAGES = readPackages();

    private SystemClassPath() {
    }

    static boolean has(String internalName) {
        int slash = internalName.lastIndexOf('/');
        if (PACKAGES != null && !PACKAGES.contains(slash < 0 ? "" : internalName.substring(0, slash))) {
            return false;
        }
        return ClassLoader.getSystemResource(internalName + ".class") != null;
    }

    private static @Nullable Set<String> readPackages() {
        Set<String> packages = new HashSet<>();
        ModuleLayer.boot().modules().forEach(module -> module.getPackages()
                .forEach(name -> packages.add(name.replace('.', '/'))));
        Deque<Path> jars = new ArrayDeque<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator, -1)) {
            if (!entry.isEmpty()) {
                jars.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
        Set<Path> read = new HashSet<>();
        while (!jars.isEmpty()) {
            Path jar = jars.poll();
            if (!read.add(jar) || !Files.exists(jar)) {
                continue;
            }
            if (!Files.isRegularFile(jar) || !readJar(jar, packages, jars)) {
                return null;
            }
        }
        return Set.copyOf(packages);
    }

    private static boolean readJar(Path jar, Set<String> packages, Deque<Path> more) {
        try (JarFile file = new JarFile(jar.toFile())) {
            file.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".class")).forEach(name -> {
                String path = name.startsWith(VERSIONED) ? name.substring(name.indexOf('/', VERSIONED.length()) + 1)
                        : name;
                int slash = path.lastIndexOf('/');
                packages.add(slash < 0 ? "" : path.substring(0, slash));
            });
            Manifest manifest = file.getManifest();
            String classPath = manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath != null) {
                for (String next : classPath.trim().split("\\s+", -1)) {
                    if (!next.isEmpty()) {
                        more.add(jar.resolveSibling(next).toAbsolutePath().normalize());
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
