package dev.moon.hotwire.jvm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class AgentJar {
    private static final String AGENT = JvmAccess.AGENT.replace('.', '/');
    private static final String INSTRUMENTATION = "Ljava/lang/instrument/Instrumentation;";

    private AgentJar() {
    }

    static Path write(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path jar = folder.resolve("agent.jar");
        Path part = folder.resolve("agent.jar." + ProcessHandle.current().pid() + ".part");
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Launcher-Agent-Class", JvmAccess.AGENT);
        main.putValue("Can-Redefine-Classes", "true");
        main.putValue("Can-Retransform-Classes", "true");
        try {
            try (OutputStream out = Files.newOutputStream(part);
                    JarOutputStream zip = new JarOutputStream(out, manifest)) {
                zip.putNextEntry(new JarEntry(AGENT + ".class"));
                zip.write(makeAgent());
                zip.closeEntry();
            }
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            if (!Files.isRegularFile(jar)) {
                throw e;
            }
        }
        return jar;
    }

    static byte[] makeAgent() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, AGENT, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "instrumentation",
                INSTRUMENTATION, null, null).visitEnd();
        MethodVisitor store = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "agentmain",
                "(Ljava/lang/String;" + INSTRUMENTATION + ")V", null, null);
        store.visitCode();
        store.visitVarInsn(Opcodes.ALOAD, 1);
        store.visitFieldInsn(Opcodes.PUTSTATIC, AGENT, "instrumentation", INSTRUMENTATION);
        store.visitInsn(Opcodes.RETURN);
        store.visitMaxs(0, 0);
        store.visitEnd();
        MethodVisitor give = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getInstrumentation",
                "()" + INSTRUMENTATION, null, null);
        give.visitCode();
        give.visitFieldInsn(Opcodes.GETSTATIC, AGENT, "instrumentation", INSTRUMENTATION);
        give.visitInsn(Opcodes.ARETURN);
        give.visitMaxs(0, 0);
        give.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
