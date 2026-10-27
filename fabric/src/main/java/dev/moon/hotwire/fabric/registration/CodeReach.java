package dev.moon.hotwire.fabric.registration;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodeReach {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodeReach.class);

    private static final Set<String> OWN_KEYS = Set.of("main", "client", "server", "preLaunch");

    private final Instrumentation instrumentation;
    private final Function<String, byte @Nullable []> bytes;

    private final Map<String, Links> reached = new ConcurrentHashMap<>();

    private final Set<String> said = ConcurrentHashMap.newKeySet();

    private record Links(Set<String> supers, Set<String> owners) {
    }

    public CodeReach(Instrumentation instrumentation, Function<String, byte @Nullable []> bytes) {
        this.instrumentation = instrumentation;
        this.bytes = bytes;
    }

    public boolean callsNow(String mod, String other) {
        Set<String> others = new HashSet<>();
        List<String> mine = new ArrayList<>();
        for (Class<?> type : this.instrumentation.getAllLoadedClasses()) {
            if (type.isArray() || type.isHidden()) {
                continue;
            }
            String owner = GameRegistrations.findModOf(type);
            if (other.equals(owner)) {
                others.add(type.getName().replace('.', '/'));
            } else if (mod.equals(owner)) {
                mine.add(type.getName().replace('.', '/'));
            }
        }
        if (others.isEmpty()) {
            return false;
        }
        List<String> handed = findHandedPackages(mod);
        for (String name : mine) {
            if (handed.stream().anyMatch(name::startsWith)) {
                continue;
            }
            Links of = this.reached.computeIfAbsent(name, this::read);
            if (of.supers().stream().noneMatch(others::contains)) {
                for (String owner : of.owners()) {
                    if (others.contains(owner)) {
                        if (this.said.add(mod + " " + other)) {
                            LOGGER.info("[Hotwire] {} runs {}'s code by its own hand ({} reaches {}), so it goes off"
                                    + " with it", mod, other, name, owner);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<String> findHandedPackages(String mod) {
        List<String> out = new ArrayList<>();
        ModMetadata metadata = FabricLoader.getInstance().getModContainer(mod).map(container -> container.getMetadata())
                .orElse(null);
        if (!(metadata instanceof LoaderModMetadata loader)) {
            return out;
        }
        for (String key : loader.getEntrypointKeys()) {
            if (OWN_KEYS.contains(key)) {
                continue;
            }
            for (EntrypointMetadata entrypoint : loader.getEntrypoints(key)) {
                String value = entrypoint.getValue();
                int member = value.indexOf("::");
                String type = member < 0 ? value : value.substring(0, member);
                int dot = type.lastIndexOf('.');
                if (dot > 0) {
                    out.add(type.substring(0, dot + 1).replace('.', '/'));
                }
            }
        }
        return out;
    }

    private Links read(String name) {
        byte[] classBytes = this.bytes.apply(name);
        if (classBytes == null) {
            return new Links(Set.of(), Set.of());
        }
        Set<String> supers = new HashSet<>();
        Set<String> owners = new HashSet<>();
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String visited, @Nullable String signature,
                        @Nullable String superName, String @Nullable [] interfaces) {
                    if (superName != null) {
                        supers.add(superName);
                    }
                    if (interfaces != null) {
                        supers.addAll(List.of(interfaces));
                    }
                }

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor,
                        @Nullable String signature, String @Nullable [] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String called, String desc,
                                boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC) {
                                owners.add(owner);
                            }
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                            if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
                                owners.add(owner);
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.NEW) {
                                owners.add(type);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            return new Links(Set.of(), Set.of());
        }
        return new Links(Set.copyOf(supers), Set.copyOf(owners));
    }
}
