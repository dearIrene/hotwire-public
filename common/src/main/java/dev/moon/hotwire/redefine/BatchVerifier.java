package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

final class BatchVerifier {
    private BatchVerifier() {
    }

    static List<String> findProblems(Map<Class<?>, byte[]> classes) {
        List<String> problems = new ArrayList<>();
        classes.forEach((type, bytes) -> {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
            Type superType = node.superName == null ? null : Type.getObjectType(node.superName);
            List<Type> interfaces = new ArrayList<>(node.interfaces.size());
            for (String face : node.interfaces) {
                interfaces.add(Type.getObjectType(face));
            }
            boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
            for (MethodNode method : node.methods) {
                if (method.instructions.size() == 0) {
                    continue;
                }
                SimpleVerifier verifier = new SimpleVerifier(Opcodes.ASM9, Type.getObjectType(node.name), superType,
                        interfaces, isInterface) {
                };
                verifier.setClassLoader(type.getClassLoader());
                try {
                    new Analyzer<BasicValue>(verifier).analyze(node.name, method);
                } catch (AnalyzerException | RuntimeException | LinkageError e) {
                    String at = e instanceof AnalyzerException refused && refused.node != null
                            ? " at instruction " + method.instructions.indexOf(refused.node) : "";
                    problems.add(node.name + "." + method.name + method.desc + at + ": " + e.getMessage());
                }
            }
        });
        if (problems.isEmpty()) {
            classes.forEach((type, bytes) -> {
                String refused = findJvmProblem(type, bytes);
                if (refused != null) {
                    problems.add(refused);
                }
            });
        }
        return problems;
    }

    static @Nullable String findJvmProblem(Class<?> type, byte[] bytes) {
        try {
            MethodHandles.privateLookupIn(type, MethodHandles.lookup()).defineHiddenClass(bytes, false);
            return null;
        } catch (VerifyError e) {
            return type.getName() + ": " + e.getMessage();
        } catch (LinkageError e) {
            return type.getName() + " did not link: " + e;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }
}
