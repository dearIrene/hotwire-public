package dev.moon.hotwire.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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

    static void checkMade(Path jar) throws IOException {
        byte[] agent = makeAgent();
        boolean found = false;
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(file.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || JarFile.MANIFEST_NAME.equals(name)) {
                    continue;
                }
                if (!name.equals(AGENT + ".class")) {
                    throw new IOException(jar + " holds " + name + ", which the agent's jar never has");
                }
                try (InputStream in = file.getInputStream(entry)) {
                    if (!Arrays.equals(in.readAllBytes(), agent)) {
                        throw new IOException(jar + " holds an agent class Hotwire did not make");
                    }
                }
                found = true;
            }
        }
        if (!found) {
            throw new IOException(jar + " holds no agent class");
        }
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
