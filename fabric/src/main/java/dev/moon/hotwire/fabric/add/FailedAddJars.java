package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.update.UpdateDiffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FailedAddJars {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailedAddJars.class);
    private static final Set<String> CLASSES = ConcurrentHashMap.newKeySet();

    private FailedAddJars() {
    }

    static void add(Path jar) {
        try {
            UpdateDiffer.readEntries(jar).keySet().stream().filter(entry -> entry.endsWith(".class"))
                    .map(entry -> entry.substring(0, entry.length() - ".class".length())).forEach(CLASSES::add);
            LOGGER.info("[Hotwire] {} stays on the class path; its classes are hidden from the plugins asked again",
                    jar.getFileName());
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] {} stays on the class path and could not be read, so its classes stay in sight",
                    jar.getFileName(), e);
        }
    }

    static void remove(Path jar) {
        try {
            UpdateDiffer.readEntries(jar).keySet().stream().filter(entry -> entry.endsWith(".class"))
                    .map(entry -> entry.substring(0, entry.length() - ".class".length())).forEach(CLASSES::remove);
        } catch (ModJarException e) {
            LOGGER.warn("[Hotwire] {} could not be read, so its classes stay hidden", jar.getFileName(), e);
        }
    }

    public static boolean isHiding(String internal) {
        return !CLASSES.isEmpty() && CLASSES.contains(internal);
    }

    public static byte @Nullable [] removeHiddenInterfaces(byte @Nullable [] bytes) {
        if (bytes == null || CLASSES.isEmpty()) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        if (Arrays.stream(reader.getInterfaces()).noneMatch(CLASSES::contains)) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, @Nullable String signature,
                    @Nullable String superName, String @Nullable [] interfaces) {
                String[] own = Arrays.stream(Objects.requireNonNull(interfaces)).filter(face -> !CLASSES.contains(face))
                        .toArray(String[]::new);

                boolean named = signature != null && Arrays.stream(interfaces)
                        .anyMatch(face -> CLASSES.contains(face) && signature.contains("L" + face + ";"));
                super.visit(version, access, name, named ? null : signature, superName, own);
            }
        }, 0);
        return writer.toByteArray();
    }
}
