package dev.moon.hotwire.redefine;

import dev.moon.hotwire.classfile.ClassNodes;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

final class BatchVerifier {
    private static final Pattern HANDLER = Pattern.compile("^[A-Za-z]+[$][a-z0-9]+[$]([A-Za-z0-9_-]+)[$]");

    private BatchVerifier() {
    }

    static Set<String> findNamed(Set<Class<?>> classes, List<String> problems) {
        Set<String> named = new HashSet<>();
        for (Class<?> type : classes) {
            String internal = Type.getInternalName(type);
            for (String problem : problems) {
                if (startsWithClass(problem, type.getName()) || startsWithClass(problem, internal)) {
                    named.add(internal);
                }
            }
        }
        return named;
    }

    private static boolean startsWithClass(String problem, String name) {
        return problem.startsWith(name) && problem.length() > name.length()
                && ".: ".indexOf(problem.charAt(name.length())) >= 0;
    }

    static List<String> findFormatProblems(Map<Class<?>, byte[]> classes) {
        List<String> problems = new ArrayList<>();
        classes.forEach((type, bytes) -> {
            String refused = findJvmProblem(type, bytes);
            if (refused != null) {
                problems.add(refused);
            }
        });
        return problems;
    }

    static List<String> findProblems(Map<Class<?>, byte[]> classes) {
        List<String> problems = new ArrayList<>();
        classes.forEach((type, bytes) -> {
            ClassNode node = ClassNodes.read(bytes, ClassReader.SKIP_DEBUG);
            for (MethodNode method : node.methods) {
                if (method.instructions.size() == 0) {
                    continue;
                }
                try {
                    verify(type, node, method);
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

    private static void verify(Class<?> type, ClassNode node, MethodNode method) throws AnalyzerException {
        Type superType = node.superName == null ? null : Type.getObjectType(node.superName);
        List<Type> interfaces = new ArrayList<>(node.interfaces.size());
        for (String face : node.interfaces) {
            interfaces.add(Type.getObjectType(face));
        }
        SimpleVerifier verifier = new SimpleVerifier(Opcodes.ASM9, Type.getObjectType(node.name), superType,
                interfaces, (node.access & Opcodes.ACC_INTERFACE) != 0) {
        };
        verifier.setClassLoader(type.getClassLoader());
        new Analyzer<BasicValue>(verifier).analyze(node.name, method);
    }

    static Map<Class<?>, Set<String>> findNotFitting(Map<Class<?>, byte[]> mixed) {
        Map<Class<?>, Set<String>> found = new LinkedHashMap<>();
        mixed.forEach((type, bytes) -> {
            ClassNode node = ClassNodes.read(bytes, ClassReader.SKIP_DEBUG);
            for (MethodNode method : node.methods) {
                String mod = method.instructions.size() == 0 ? null : findHandlerMod(findRefused(type, node, method));
                if (mod != null) {
                    found.computeIfAbsent(type, any -> new TreeSet<>()).add(mod);
                }
            }
        });
        return found;
    }

    private static @Nullable AbstractInsnNode findRefused(Class<?> type, ClassNode node, MethodNode method) {
        try {
            verify(type, node, method);
            return null;
        } catch (AnalyzerException e) {
            return e.node;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static @Nullable String findHandlerMod(@Nullable AbstractInsnNode at) {
        String name = at instanceof MethodInsnNode call ? call.name
                : at instanceof InvokeDynamicInsnNode dynamic ? dynamic.name : null;
        Matcher handler = name == null ? null : HANDLER.matcher(name);
        return handler != null && handler.find() ? handler.group(1) : null;
    }

    static @Nullable String findJvmProblem(Class<?> type, byte[] bytes) {
        try {
            MethodHandles.privateLookupIn(type, MethodHandles.lookup()).defineHiddenClass(bytes, false);
            return null;
        } catch (VerifyError e) {
            String message = String.valueOf(e.getMessage());
            return message.contains("'" + Type.getInternalName(type) + "+0x") ? null
                    : type.getName() + ": " + message;
        } catch (LinkageError e) {
            return type.getName() + " did not link: " + e;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }
}
