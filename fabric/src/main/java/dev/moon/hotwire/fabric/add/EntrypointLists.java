package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.redefine.FinalWrites;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EntrypointLists {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntrypointLists.class);

    private static final Set<String> RUN_BY_ADD = Set.of("main", "client", "server", "preLaunch");

    private EntrypointLists() {
    }

    static void join(List<String> ids, Function<String, @Nullable Class<?>> findLoaded) {
        FabricLoader loader = FabricLoader.getInstance();
        for (String key : findKeys(ids)) {
            String host = key.substring(0, key.indexOf(':'));

            if (ids.contains(host) || !loader.isModLoaded(host)) {
                continue;
            }
            List<Object> added = new ArrayList<>();
            for (EntrypointContainer<Object> entry : loader.getEntrypointContainers(key, Object.class)) {
                if (ids.contains(entry.getProvider().getMetadata().getId())) {
                    added.add(entry.getEntrypoint());
                }
            }
            loader.getModContainer(host).ifPresent(container -> findFilledBy(container, key).forEach(
                    (owner, fields) -> fields.forEach(name -> join(findLoaded.apply(owner), name, key, added))));
        }
    }

    private static Set<String> findKeys(List<String> ids) {
        Set<String> keys = new LinkedHashSet<>();
        for (String id : ids) {
            FabricLoader.getInstance().getModContainer(id).ifPresent(container -> {
                if (container.getMetadata() instanceof LoaderModMetadata metadata) {
                    metadata.getEntrypointKeys().stream()
                            .filter(key -> !RUN_BY_ADD.contains(key) && key.indexOf(':') > 0).forEach(keys::add);
                }
            });
        }
        return keys;
    }

    private static Map<String, List<String>> findFilledBy(ModContainer host, String key) {
        Map<String, List<String>> filled = new LinkedHashMap<>();
        byte[] named = key.getBytes(StandardCharsets.UTF_8);

        Set<ModContainer> containers = new LinkedHashSet<>(List.of(host));
        containers.addAll(host.getContainedMods());
        String id = host.getMetadata().getId();
        FabricLoader.getInstance().getAllMods().stream().filter(mod -> mod.getMetadata().getId().startsWith(id + "-")
                || mod.getMetadata().getId().startsWith(id + "_")).forEach(containers::add);
        for (ModContainer container : containers) {
            for (Path root : container.getRootPaths()) {
                try (Stream<Path> files = Files.walk(root)) {
                    files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                        byte[] bytes = read(file);
                        if (bytes != null && contains(bytes, named)) {
                            ClassNode node = new ClassNode();
                            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                            List<String> fields = findFilled(node, key);
                            if (!fields.isEmpty()) {
                                filled.put(node.name, fields);
                            }
                        }
                    });
                } catch (IOException e) {
                    LOGGER.info("[Hotwire] {}'s classes could not be read for its {} lists: {}",
                            container.getMetadata().getId(), key, e.toString());
                }
            }
        }
        return filled;
    }

    private static List<String> findFilled(ClassNode node, String key) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            boolean named = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    named = text.equals(key);
                } else if (named && insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode put
                        && put.owner.equals(node.name)) {
                    fields.add(put.name);
                    named = false;
                }
            }
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static void join(@Nullable Class<?> owner, String name, String key, List<Object> added) {
        if (owner == null || added.isEmpty()) {
            return;
        }
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            if (!Modifier.isStatic(field.getModifiers()) || !(field.get(null) instanceof List<?> current)) {
                return;
            }

            Set<Object> present = Collections.newSetFromMap(new IdentityHashMap<>());
            present.addAll(current);
            List<Object> fresh = added.stream().filter(entry -> !present.contains(entry)).toList();
            if (fresh.isEmpty()) {
                return;
            }
            try {
                ((List<Object>) current).addAll(fresh);
            } catch (UnsupportedOperationException fixed) {
                List<Object> joined = new ArrayList<>(current);
                joined.addAll(fresh);
                FinalWrites.write(field, null, List.copyOf(joined));
            }
            LOGGER.info("[Hotwire] {}.{} holds the added mods' {} entrypoints too", owner.getName(), name, key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] {}.{} could not be given the added mods' {} entrypoints; they apply after a"
                    + " restart", owner.getName(), name, key, e);
        }
    }

    private static byte @Nullable [] read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        outer:
        for (int at = 0; at <= bytes.length - part.length; at++) {
            for (int i = 0; i < part.length; i++) {
                if (bytes[at + i] != part[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
