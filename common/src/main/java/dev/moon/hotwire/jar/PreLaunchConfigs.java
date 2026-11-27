package dev.moon.hotwire.jar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class PreLaunchConfigs {
    private static final String MIXINS = "org/spongepowered/asm/mixin/Mixins";

    private PreLaunchConfigs() {
    }

    static byte[] addTo(byte[] manifestBytes, ModManifest manifest, ModJarReader.EntryReader entries)
            throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (ModManifest.Entrypoint entrypoint : manifest.entrypoints().getOrDefault("preLaunch", List.of())) {
            String value = entrypoint.value();
            int method = value.indexOf("::");
            byte[] bytes = entries.read((method < 0 ? value : value.substring(0, method)).replace('.', '/')
                    + ".class");
            if (bytes != null) {
                found.addAll(findRegistered(bytes));
            }
        }
        manifest.mixinConfigs().forEach(ref -> found.remove(ref.config()));
        if (found.isEmpty()) {
            return manifestBytes;
        }
        JsonObject root = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonElement had = root.get("mixins");
        JsonArray mixins = had != null && had.isJsonArray() ? had.getAsJsonArray() : new JsonArray();
        found.forEach(mixins::add);
        root.add("mixins", mixins);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Set<String> findRegistered(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        Set<String> names = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(MIXINS) && call.name.equals("addConfiguration")
                        && call.desc.startsWith("(Ljava/lang/String;")) {
                    String name = findConstantBefore(call);
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static @Nullable String findConstantBefore(MethodInsnNode call) {
        AbstractInsnNode before = call.getPrevious();
        while (before != null && before.getOpcode() < 0) {
            before = before.getPrevious();
        }
        return before instanceof LdcInsnNode constant && constant.cst instanceof String name ? name : null;
    }
}
