package dev.moon.hotwire.jar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class ContentScan {
    private static final Map<String, String> BASES = Map.of(
            "net/minecraft/world/level/block/Block", "blocks", "net/minecraft/class_2248", "blocks",
            "net/minecraft/world/item/Item", "items", "net/minecraft/class_1792", "items",
            "net/minecraft/world/level/material/Fluid", "fluids", "net/minecraft/class_3611", "fluids",
            "net/minecraft/world/entity/Entity", "entities", "net/minecraft/class_1297", "entities",
            "net/minecraft/world/level/block/entity/BlockEntity", "block entities",
            "net/minecraft/class_2586", "block entities");

    private static final Set<String> MADE_BY_CONSTRUCTING = Set.of("blocks", "items", "fluids");

    private static final Map<String, String> REGISTRY_OF = Map.of("BLOCK", "blocks", "ITEM", "items", "FLUID",
            "fluids", "ENTITY_TYPE", "entities", "BLOCK_ENTITY_TYPE", "block entities");
    private static final Set<String> REGISTRY_HOLDERS = Set.of("net/minecraft/core/registries/BuiltInRegistries",
            "net/minecraft/core/registries/Registries");

    private ContentScan() {
    }

    public static Set<String> findKinds(Map<String, byte[]> classes, Function<String, @Nullable String> gameSuperOf) {
        Map<String, String> superOf = new HashMap<>();
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassNode node = new ClassNode();
            try {
                new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException unreadable) {
                continue;
            }
            nodes.put(node.name, node);
            if (node.superName != null) {
                superOf.put(node.name, node.superName);
            }
        }
        Map<String, @Nullable String> kindOf = new HashMap<>();
        Function<String, @Nullable String> kind = name -> findKindOf(name, superOf, gameSuperOf, kindOf);
        Set<String> kinds = new TreeSet<>();
        Set<String> registered = new TreeSet<>();
        boolean obfuscated = false;
        for (ClassNode node : nodes.values()) {
            registered.addAll(findRegistered(node));
            obfuscated |= node.superName != null && node.superName.startsWith("net/minecraft/class_");
            if ((node.access & Opcodes.ACC_INTERFACE) != 0) {
                continue;
            }

            String own = (node.access & Opcodes.ACC_ABSTRACT) != 0 ? null : kind.apply(node.name);
            if (own != null && !BASES.containsKey(node.name)) {
                kinds.add(own);
            }
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    String made = null;
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                        made = kind.apply(type.desc);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle && handle.getName().equals("<init>")) {
                                made = kind.apply(handle.getOwner());
                            }
                        }
                    }
                    if (made != null && MADE_BY_CONSTRUCTING.contains(made)) {
                        kinds.add(made);
                    }
                }
            }
        }
        if (!obfuscated) {
            kinds.retainAll(registered);
        }
        return kinds;
    }

    private static Set<String> findRegistered(ClassNode node) {
        Set<String> named = new TreeSet<>();
        boolean registers = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && REGISTRY_HOLDERS.contains(field.owner)
                        && REGISTRY_OF.containsKey(field.name)) {
                    named.add(REGISTRY_OF.get(field.name));
                } else if (insn instanceof MethodInsnNode call && (call.name.startsWith("register")
                        || call.name.equals("create"))) {
                    registers = true;
                }
            }
        }
        return registers ? named : Set.of();
    }

    private static @Nullable String findKindOf(String name, Map<String, String> superOf,
            Function<String, @Nullable String> gameSuperOf, Map<String, @Nullable String> known) {
        String at = name;
        for (int depth = 0; at != null && depth < 32; depth++) {
            String base = BASES.get(at);
            if (base != null) {
                known.put(name, base);
                return base;
            }
            if (known.containsKey(at)) {
                String found = known.get(at);
                known.put(name, found);
                return found;
            }
            String up = superOf.get(at);
            at = up != null ? up : at.startsWith("net/minecraft/") ? gameSuperOf.apply(at) : null;
        }
        known.put(name, null);
        return null;
    }

    public static Map<String, byte[]> readClasses(Path jar) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (InputStream in = Files.newInputStream(jar)) {
            collect(in, classes, 0);
        }
        return classes;
    }

    private static void collect(InputStream in, Map<String, byte[]> into, int depth) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                into.putIfAbsent(name.substring(0, name.length() - ".class".length()), zip.readAllBytes());
            } else if (name.endsWith(".jar") && depth < ModJarReader.MAX_DEPTH) {
                collect(new ByteArrayInputStream(zip.readAllBytes()), into, depth + 1);
            }
        }
    }
}
