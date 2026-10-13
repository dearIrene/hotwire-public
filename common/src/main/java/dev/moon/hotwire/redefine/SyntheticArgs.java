package dev.moon.hotwire.redefine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

public final class SyntheticArgs {
    static final String PREFIX = "org/spongepowered/asm/synthetic/args/Args$";

    private SyntheticArgs() {
    }

    static Map<String, byte[]> rename(Map<String, byte[]> classes, Map<String, String> names) {
        Map<String, String> renames = new HashMap<>();
        names.forEach((from, to) -> {
            if (!from.equals(to)) {
                renames.put(from, to);
            }
        });
        if (renames.isEmpty()) {
            return classes;
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        SimpleRemapper remapper = new SimpleRemapper(renames);
        classes.forEach((name, bytes) -> {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
            out.put(name, writer.toByteArray());
        });
        return out;
    }
}
