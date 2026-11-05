package dev.moon.hotwire.redefine;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

public final class OpenGameClasses implements ClassFileTransformer {
    private static final Set<String> OPENED = ConcurrentHashMap.newKeySet();

    private final ClassLoader game;

    public OpenGameClasses(ClassLoader game) {
        this.game = game;
    }

    public static boolean isOpened(String internalName) {
        return OPENED.contains(internalName);
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public byte @Nullable [] transform(@Nullable ClassLoader loader, @Nullable String name,
            @Nullable Class<?> redefining, @Nullable ProtectionDomain domain, byte[] bytes) {
        if (name == null || loader != this.game || !isGameClass(name)
                || (redefining != null && !OPENED.contains(name))) {
            return null;
        }
        byte[] opened = open(bytes);
        if (opened != null) {
            OPENED.add(name);
        }
        return opened;
    }

    private static boolean isGameClass(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("com/mojang/");
    }

    static byte @Nullable [] open(byte[] bytes) {
        int at = findAccessAt(bytes);
        int access = readShort(bytes, at);
        if ((access & Opcodes.ACC_FINAL) == 0 || (access & Opcodes.ACC_ENUM) != 0
                || "java/lang/Record".equals(new ClassReader(bytes).getSuperName())) {
            return null;
        }
        byte[] opened = bytes.clone();
        int left = access & ~Opcodes.ACC_FINAL;
        opened[at] = (byte) (left >>> 8);
        opened[at + 1] = (byte) left;
        return opened;
    }

    private static int findAccessAt(byte[] bytes) {
        int count = readShort(bytes, 8);
        int at = 10;
        int entry = 1;
        while (entry < count) {
            switch (bytes[at]) {
                case 1 -> at += 3 + readShort(bytes, at + 1);
                case 5, 6 -> {
                    at += 9;
                    entry++;
                }
                case 7, 8, 16, 19, 20 -> at += 3;
                case 15 -> at += 4;
                default -> at += 5;
            }
            entry++;
        }
        return at;
    }

    private static int readShort(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }
}
