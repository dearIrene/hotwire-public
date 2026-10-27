package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class LoadedChecks {
    private static final Set<String> ASKS = Set.of("isModLoaded", "isLoaded", "getModContainer");

    private static final int REACH = 6;

    public enum Kind {
        SETUP,

        REMEMBERED,

        SET_ONCE
    }

    public record Ask(String field, Kind kind) {
    }

    private LoadedChecks() {
    }

    public static List<Ask> find(byte[] bytes, Set<String> mods) {
        ClassNode node = RetainedInitializers.read(bytes);
        Map<String, Ask> found = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            List<String> answers = findAnswers(method.instructions, node.name, mods);
            if (answers.isEmpty()) {
                continue;
            }
            boolean setup = method.name.equals("<clinit>");
            Map<String, InsnList> slices = setup
                    ? FieldInitializers.findStaticSlices(node.name, node, Set.copyOf(answers)) : Map.of();
            String guard = setup ? null : findGuard(method, node.name);
            for (String field : answers) {
                if (slices.containsKey(field)) {
                    found.putIfAbsent(field, new Ask(field, Kind.SETUP));
                } else if (isBoolean(field)) {
                    found.putIfAbsent(field, new Ask(field, guard != null ? Kind.REMEMBERED : Kind.SET_ONCE));
                }
            }
            if (guard != null) {
                found.putIfAbsent(guard, new Ask(guard, Kind.REMEMBERED));
            }
        }
        return List.copyOf(found.values());
    }

    private static boolean isBoolean(String field) {
        return field.endsWith("Z") && !field.endsWith("[Z");
    }

    private static List<String> findAnswers(InsnList code, String owner, Set<String> mods) {
        List<String> answers = new ArrayList<>();
        int sinceId = -1;
        int sinceAsk = -1;
        for (AbstractInsnNode insn : code) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String id) {
                sinceId = mods.contains(id) ? 0 : -1;
                sinceAsk = -1;
                continue;
            }
            if (sinceId >= 0 && insn instanceof MethodInsnNode call && ASKS.contains(call.name)) {
                sinceAsk = 0;
            } else if (sinceAsk >= 0 && insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC
                    && put.owner.equals(owner)) {
                if (!answers.contains(put.name + put.desc)) {
                    answers.add(put.name + put.desc);
                }
                sinceAsk = -1;
            }
            sinceId = sinceId < 0 || ++sinceId > REACH ? -1 : sinceId;
            sinceAsk = sinceAsk < 0 || ++sinceAsk > REACH * 2 ? -1 : sinceAsk;
        }
        return answers;
    }

    private static @Nullable String findGuard(MethodNode method, String owner) {
        String read = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(owner)) {
                if (read == null && field.getOpcode() == Opcodes.GETSTATIC && isBoolean(field.name + field.desc)) {
                    read = field.name + field.desc;
                } else if (field.getOpcode() == Opcodes.PUTSTATIC && read != null
                        && read.equals(field.name + field.desc)) {
                    return read;
                }
            }
        }
        return null;
    }
}
