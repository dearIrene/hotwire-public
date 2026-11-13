package dev.moon.hotwire.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class OwnPatch {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private final String name;
    private final byte[] bytes;
    private final Set<String> targets;
    private final boolean following;

    private OwnPatch(String name, byte[] bytes, Set<String> targets, boolean following) {
        this.name = name;
        this.bytes = bytes;
        this.targets = targets;
        this.following = following;
    }

    public String getName() {
        return this.name;
    }

    byte[] getBytes() {
        return this.bytes;
    }

    public Set<String> getTargets() {
        return this.targets;
    }

    public boolean canFollow() {
        return this.following;
    }

    public static OwnPatch read(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        Set<String> targets = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return !descriptor.equals(MIXIN) ? null : new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String unnamed, Object value) {
                                targets.add(value instanceof Type type ? type.getInternalName()
                                        : String.valueOf(value).replace('.', '/'));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean follows = (reader.getAccess() & Opcodes.ACC_INTERFACE) == 0 && reader.getInterfaces().length == 0;
        return new OwnPatch(reader.getClassName(), bytes, Set.copyOf(targets), follows);
    }

    public static List<OwnPatch> readAll(ClassLoader loader, String config) throws IOException {
        List<OwnPatch> patches = new ArrayList<>();
        JsonObject root;
        try (InputStream in = loader.getResourceAsStream(config)) {
            if (in == null) {
                return patches;
            }
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String folder = root.get("package").getAsString().replace('.', '/') + "/";
        for (String list : List.of("mixins", "client", "server")) {
            JsonArray names = root.getAsJsonArray(list);
            for (JsonElement name : names == null ? new JsonArray() : names) {
                try (InputStream in = loader.getResourceAsStream(folder + name.getAsString().replace('.', '/')
                        + ".class")) {
                    if (in != null) {
                        patches.add(read(in.readAllBytes()));
                    }
                }
            }
        }
        return patches;
    }
}
