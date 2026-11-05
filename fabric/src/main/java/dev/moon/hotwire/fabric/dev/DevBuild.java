package dev.moon.hotwire.fabric.dev;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DevBuild {
    record Diff(SortedSet<String> changedClasses, SortedSet<String> addedClasses, SortedSet<String> removedClasses,
            SortedSet<String> changedFiles) {
        boolean isEmpty() {
            return this.changedClasses.isEmpty() && this.addedClasses.isEmpty() && this.removedClasses.isEmpty()
                    && this.changedFiles.isEmpty();
        }

        boolean hasClasses() {
            return !this.changedClasses.isEmpty() || !this.addedClasses.isEmpty() || !this.removedClasses.isEmpty();
        }
    }

    private final Path jar;
    private final Map<String, Long> sums;

    private DevBuild(Path jar, Map<String, Long> sums) {
        this.jar = jar;
        this.sums = sums;
    }

    Path getJar() {
        return this.jar;
    }

    static DevBuild take(List<Path> folders, Path jar) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = folder.relativize(file).toString().replace('\\', '/');
                    if (!entries.containsKey(name)) {
                        entries.put(name, Files.readAllBytes(file));
                    }
                }
            }
        }
        Files.createDirectories(jar.getParent());
        Map<String, Long> sums = new HashMap<>();
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                byte[] bytes = entry.getValue();
                CRC32 crc = new CRC32();
                crc.update(bytes);

                ZipEntry zipped = new ZipEntry(entry.getKey());
                zipped.setMethod(ZipEntry.STORED);
                zipped.setSize(bytes.length);
                zipped.setCompressedSize(bytes.length);
                zipped.setCrc(crc.getValue());
                zip.putNextEntry(zipped);
                zip.write(bytes);
                zip.closeEntry();
                sums.put(entry.getKey(), crc.getValue() ^ ((long) bytes.length << 32));
            }
        }
        return new DevBuild(jar, sums);
    }

    Diff diff(DevBuild newer) {
        SortedSet<String> changed = new TreeSet<>();
        SortedSet<String> added = new TreeSet<>();
        SortedSet<String> removed = new TreeSet<>();
        SortedSet<String> files = new TreeSet<>();
        newer.sums.forEach((name, sum) -> {
            Long before = this.sums.get(name);
            if (before != null && before.equals(sum)) {
                return;
            }
            boolean isClass = name.endsWith(".class");
            if (!isClass) {
                files.add(name);
            } else if (before == null) {
                added.add(name);
            } else {
                changed.add(name);
            }
        });
        this.sums.keySet().forEach(name -> {
            if (!newer.sums.containsKey(name)) {
                if (name.endsWith(".class")) {
                    removed.add(name);
                } else {
                    files.add(name);
                }
            }
        });
        return new Diff(changed, added, removed, files);
    }
}
