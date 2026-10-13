package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

final class GeneratedNames {
    private static final Pattern NUMBERS = Pattern.compile(
            "(?<!_\\$(?:md|fd))\\$(?!lambda\\$)[a-z]{3}[0-9a-f]{3}\\$"
            + "|(?<=_\\$(?:md|fd)\\$[0-9a-f]{1,8})\\$[0-9a-f]+$|\\$\\d+");
    private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private GeneratedNames() {
    }

    static byte[] matchRunning(byte[] running, byte[] mixed) {
        return matchRunning(running, null, mixed);
    }

    static byte[] matchRunning(byte[] running, byte @Nullable [] previousMixed, byte[] mixed) {
        ClassNode was = read(running);
        ClassNode now = read(mixed);
        List<Member> runningMethods = listMembers(was.methods);
        List<Member> runningFields = listFields(was.fields);
        if (previousMixed != null) {
            ClassNode last = read(previousMixed);
            addMissing(runningMethods, listMembers(last.methods));
            addMissing(runningFields, listFields(last.fields));
        }
        Map<String, String> methods = pairUp(runningMethods, listMembers(now.methods));
        Map<String, String> fields = pairUp(runningFields, listFields(now.fields));
        if (methods.isEmpty() && fields.isEmpty()) {
            return mixed;
        }
        String owner = now.name;
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(mixed).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String mapMethodName(String on, String name, String descriptor) {
                return on.equals(owner) ? methods.getOrDefault(name + descriptor, name) : name;
            }

            @Override
            public String mapFieldName(String on, String name, String descriptor) {
                return on.equals(owner) ? fields.getOrDefault(name + descriptor, name) : name;
            }
        }), 0);
        return writer.toByteArray();
    }

    static byte[] matchReferences(byte[] mixed, Function<String, byte @Nullable []> runningOf,
            Function<String, Set<String>> movedOf) {
        ClassNode now = read(mixed);
        String self = now.name;
        Set<String> declared = new HashSet<>();
        now.methods.forEach(method -> declared.add(method.name + method.desc));
        now.fields.forEach(field -> declared.add(field.name + field.desc));
        if (!namesNumbered(mixed, self, declared)) {
            return mixed;
        }
        Map<String, RunningMembers> owners = new HashMap<>();
        Function<String, RunningMembers> membersOf = owner -> owners.computeIfAbsent(owner,
                on -> RunningMembers.of(runningOf.apply(on), movedOf.apply(on)));
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(mixed).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String mapMethodName(String on, String name, String descriptor) {
                return findRunningName(on, name, descriptor, self, declared, now.superName, membersOf);
            }

            @Override
            public String mapFieldName(String on, String name, String descriptor) {
                return findRunningName(on, name, descriptor, self, declared, now.superName, membersOf);
            }
        }), 0);
        return writer.toByteArray();
    }

    private static boolean namesNumbered(byte[] bytes, String self, Set<String> declared) {
        boolean[] found = new boolean[1];
        MethodVisitor code = new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean face) {
                found[0] |= isNumbered(self, owner, name, descriptor, declared);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                found[0] |= isNumbered(self, owner, name, descriptor, declared);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                for (Object argument : arguments) {
                    found[0] |= argument instanceof Handle handle
                            && isNumbered(self, handle.getOwner(), handle.getName(), handle.getDesc(), declared);
                }
            }

            @Override
            public void visitLdcInsn(Object value) {
                found[0] |= value instanceof Handle handle
                        && isNumbered(self, handle.getOwner(), handle.getName(), handle.getDesc(), declared);
            }
        };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, @Nullable String signature,
                    String @Nullable [] exceptions) {
                return code;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean isNumbered(String self, String owner, String name, String descriptor, Set<String> declared) {
        return NUMBERS.matcher(name).find() && !(owner.equals(self) && declared.contains(name + descriptor));
    }

    private static String findRunningName(String owner, String name, String descriptor, String self,
            Set<String> declared, @Nullable String selfSuper, Function<String, RunningMembers> membersOf) {
        if (!NUMBERS.matcher(name).find() || (owner.equals(self) && declared.contains(name + descriptor))) {
            return name;
        }
        String key = mask(name) + descriptor;
        String at = owner.equals(self) ? selfSuper : owner;

        for (int depth = 0; at != null && !at.startsWith("java/") && depth < 64; depth++) {
            RunningMembers members = membersOf.apply(at);
            List<String> matches = members.byKey().get(key);
            if (matches != null) {
                return matches.size() == 1 ? matches.getFirst() : name;
            }
            at = members.superName();
        }
        return name;
    }

    private record RunningMembers(Map<String, List<String>> byKey, @Nullable String superName) {
        static RunningMembers of(byte @Nullable [] running, Set<String> moved) {
            if (running == null && moved.isEmpty()) {
                return new RunningMembers(Map.of(), null);
            }
            Map<String, List<String>> members = running == null ? new HashMap<>() : findMaskedMembers(running);
            for (String member : moved) {
                int descriptor = member.indexOf('(');
                if (descriptor < 0 || !NUMBERS.matcher(member.substring(0, descriptor)).find()) {
                    continue;
                }
                String name = member.substring(0, descriptor);
                members.computeIfAbsent(mask(name) + member.substring(descriptor), key -> new ArrayList<>()).add(name);
            }
            return new RunningMembers(members, running == null ? null : read(running).superName);
        }
    }

    private static void addMissing(List<Member> into, List<Member> more) {
        Set<String> had = new HashSet<>();
        into.forEach(member -> had.add(member.name() + member.desc()));
        for (Member member : more) {
            if (had.add(member.name() + member.desc())) {
                into.add(member);
            }
        }
    }

    private static String mask(String name) {
        return NUMBERS.matcher(name).replaceAll("\\$#");
    }

    private static Map<String, List<String>> findMaskedMembers(byte[] running) {
        Map<String, List<String>> members = new HashMap<>();
        ClassNode node = read(running);
        for (MethodNode method : node.methods) {
            if (NUMBERS.matcher(method.name).find()) {
                members.computeIfAbsent(mask(method.name) + method.desc,
                        key -> new ArrayList<>()).add(method.name);
            }
        }
        for (FieldNode field : node.fields) {
            if (NUMBERS.matcher(field.name).find()) {
                members.computeIfAbsent(mask(field.name) + field.desc,
                        key -> new ArrayList<>()).add(field.name);
            }
        }
        return members;
    }

    private record Member(String name, String desc, boolean isStatic, @Nullable String mixin) {
        String key() {
            return mask(this.name) + this.desc + this.isStatic + this.mixin;
        }
    }

    private static Map<String, String> pairUp(List<Member> running, List<Member> mixed) {
        Set<String> both = new HashSet<>();
        running.forEach(member -> both.add(member.name() + member.desc()));
        Set<String> inMixed = new HashSet<>();
        mixed.forEach(member -> inMixed.add(member.name() + member.desc()));
        Map<String, List<Member>> onlyRunning = groupByKey(running, inMixed);
        Map<String, List<Member>> onlyMixed = groupByKey(mixed, both);
        Map<String, String> renames = new HashMap<>();
        onlyMixed.forEach((key, members) -> {
            List<Member> old = onlyRunning.get(key);
            if (members.size() == 1 && old != null && old.size() == 1 && !old.getFirst().name()
                    .equals(members.getFirst().name())) {
                renames.put(members.getFirst().name() + members.getFirst().desc(), old.getFirst().name());
            }
        });
        return renames;
    }

    private static Map<String, List<Member>> groupByKey(List<Member> members, Set<String> inOther) {
        Map<String, List<Member>> grouped = new HashMap<>();
        for (Member member : members) {
            if (!inOther.contains(member.name() + member.desc()) && NUMBERS.matcher(member.name()).find()) {
                grouped.computeIfAbsent(member.key(), key -> new ArrayList<>()).add(member);
            }
        }
        return grouped;
    }

    private static List<Member> listMembers(List<MethodNode> methods) {
        List<Member> out = new ArrayList<>();
        for (MethodNode method : methods) {
            out.add(new Member(method.name, method.desc, (method.access & Opcodes.ACC_STATIC) != 0,
                    findMergedFrom(method.visibleAnnotations)));
        }
        return out;
    }

    private static List<Member> listFields(List<FieldNode> fields) {
        List<Member> out = new ArrayList<>();
        for (FieldNode field : fields) {
            out.add(new Member(field.name, field.desc, (field.access & Opcodes.ACC_STATIC) != 0,
                    findMergedFrom(field.visibleAnnotations)));
        }
        return out;
    }

    static @Nullable String findMergedFrom(@Nullable List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(MIXIN_MERGED) && annotation.values != null) {
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (Objects.equals(annotation.values.get(i), "mixin")
                            && annotation.values.get(i + 1) instanceof String name) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }
}
