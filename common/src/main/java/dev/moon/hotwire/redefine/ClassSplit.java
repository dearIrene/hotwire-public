package dev.moon.hotwire.redefine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ClassSplit {
    private static final String LINKER = Linker.class.getName().replace('.', '/');
    private static final String BOOTSTRAP = "Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;";
    private static final Handle METHOD = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapMethod",
            "(" + BOOTSTRAP + ")Ljava/lang/invoke/CallSite;", false);
    private static final Handle FIELD = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapField",
            "(" + BOOTSTRAP + "ILjava/lang/String;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle LAMBDA = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapLambda",
            "(" + BOOTSTRAP + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle SUPER = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapSuperCall",
            "(" + BOOTSTRAP + "Ljava/lang/Class;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle INHERITED = new Handle(Opcodes.H_INVOKESTATIC, LINKER, "bootstrapInherited",
            "(" + BOOTSTRAP + "Ljava/lang/Class;I)Ljava/lang/invoke/CallSite;", false);
    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    @SuppressWarnings("ArrayRecordComponent")

    public record Result(byte[] target, byte[] sidecar, List<String> moved, List<String> stubbed, Set<String> ducks,
            byte @Nullable [] fieldHolder, Set<String> movedInstance, boolean rehashes) {
    }

    private static final Set<String> HASHING = Set.of("hashCode()I", "equals(Ljava/lang/Object;)Z");

    private final String target;
    private final String sidecar;
    private final Set<String> addedFields;

    private final Function<String, Set<String>> addedElsewhere;
    private final Map<String, MethodNode> addedMethods;

    private final Set<String> declared;

    private final Set<String> stillFinal;

    private final Set<String> writtenFinals;
    private final Set<String> writtenInPlace;

    private ClassSplit(String target, Set<String> addedFields, Function<String, Set<String>> addedElsewhere,
            Map<String, MethodNode> addedMethods, Set<String> declared, Set<String> stillFinal,
            Set<String> writtenFinals, Set<String> writtenInPlace) {
        this.target = target;
        this.sidecar = target + "$hotwire";
        this.addedFields = addedFields;
        this.addedElsewhere = addedElsewhere;
        this.addedMethods = addedMethods;
        this.declared = declared;
        this.stillFinal = stillFinal;
        this.writtenFinals = writtenFinals;
        this.writtenInPlace = writtenInPlace;
    }

    static Result split(byte[] runningBytes, byte[] mixedBytes) throws SplitException {
        return split(runningBytes, mixedBytes, name -> null, name -> null, Set.of(), Set.of(), name -> Set.of());
    }

    static Result split(byte[] runningBytes, byte[] mixedBytes, Function<String, byte @Nullable []> classBytes,
            Function<String, byte @Nullable []> mixedOf, Set<String> batchDucks, Set<String> batchMoved,
            Function<String, Set<String>> addedElsewhere) throws SplitException {
        ClassNode running = read(runningBytes);
        ClassNode mixed = read(GeneratedNames.matchRunning(runningBytes, mixedBytes));
        if (!running.name.equals(mixed.name)) {
            throw new SplitException(running.name + " was mixed as " + mixed.name);
        }
        Set<String> runningFields = new HashSet<>();
        running.fields.forEach(f -> runningFields.add(f.name + f.desc));
        Set<String> runningMethods = new HashSet<>();
        Set<String> runningStatic = new HashSet<>();
        running.methods.forEach(m -> {
            runningMethods.add(m.name + m.desc);
            if ((m.access & Opcodes.ACC_STATIC) != 0) {
                runningStatic.add(m.name + m.desc);
            }
        });

        Predicate<MethodNode> inRunning = m -> runningMethods.contains(m.name + m.desc)
                && runningStatic.contains(m.name + m.desc) == ((m.access & Opcodes.ACC_STATIC) != 0);
        Set<String> addedFields = new HashSet<>();
        List<FieldNode> holderFields = new ArrayList<>();
        for (FieldNode field : mixed.fields) {
            if (!runningFields.contains(field.name + field.desc)) {
                addedFields.add(field.name + field.desc);
                holderFields.add(field);
            }
        }
        Map<String, MethodNode> mixedMethods = new LinkedHashMap<>();
        Map<String, MethodNode> addedMethods = new LinkedHashMap<>();
        for (MethodNode method : mixed.methods) {
            mixedMethods.put(method.name + method.desc, method);
            if (!inRunning.test(method)) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    throw new SplitException(running.name + "." + method.name + " is abstract or native and cannot be"
                            + " moved to a sidecar");
                }
                if (method.name.equals("<init>")) {
                    throw new SplitException(running.name + " gains a constructor " + method.desc + ", which cannot be"
                            + " moved to a sidecar");
                }
                addedMethods.put(method.name + method.desc, method);
            }
        }
        Set<String> ducks = DuckParents.close(mixed.interfaces, running.interfaces, classBytes);
        Set<String> allDucks = new HashSet<>(Linker.getDucks());
        allDucks.addAll(ducks);
        allDucks.addAll(batchDucks);

        Set<String> fromParents = ducks.isEmpty() ? Set.of() : InheritedMethods.find(mixed.superName, mixedOf);
        for (String duck : ducks) {
            byte[] bytes = classBytes.apply(duck);
            if (bytes == null) {
                continue;
            }
            for (MethodNode inherited : read(bytes).methods) {
                boolean concrete = (inherited.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC
                        | Opcodes.ACC_PRIVATE)) == 0
                        && !inherited.name.equals("<clinit>");
                if (concrete && !mixedMethods.containsKey(inherited.name + inherited.desc)
                        && !fromParents.contains(inherited.name + inherited.desc)) {
                    addedMethods.putIfAbsent(inherited.name + inherited.desc, inherited);
                }
            }
        }
        WrittenFinals.Routes routes = WrittenFinals.find(running, mixed, classBytes);
        Set<String> written = routes.inHolder();
        for (FieldNode field : running.fields) {
            if (written.contains(field.name + field.desc)) {
                addedFields.add(field.name + field.desc);
                holderFields.add(field);
            }
        }
        Set<String> declared = new HashSet<>(runningFields);
        declared.addAll(runningMethods);
        ClassSplit split = new ClassSplit(running.name, addedFields, addedElsewhere, addedMethods, declared,
                findStillFinal(running), written, routes.throughWriter());

        ClassNode out = new ClassNode();
        running.accept(out);

        Set<String> inner = new HashSet<>();
        out.innerClasses.forEach(listed -> inner.add(listed.name));
        for (InnerClassNode listed : mixed.innerClasses) {
            if (inner.add(listed.name)) {
                out.innerClasses.add(listed);
            }
        }

        Set<String> mixedMixins = new HashSet<>();
        for (MethodNode method : mixed.methods) {
            String merged = GeneratedNames.findMergedFrom(method.visibleAnnotations);
            if (merged != null) {
                mixedMixins.add(merged);
            }
        }
        List<String> stubbed = new ArrayList<>();
        List<MethodNode> bodies = new ArrayList<>();
        boolean rehashes = false;
        for (MethodNode shape : out.methods) {
            MethodNode body = new MethodNode(shape.access, shape.name, shape.desc, shape.signature,
                    shape.exceptions.toArray(String[]::new));

            body.visibleAnnotations = shape.visibleAnnotations;
            body.invisibleAnnotations = shape.invisibleAnnotations;
            MethodNode from = mixedMethods.get(shape.name + shape.desc);
            if (from == null || !inRunning.test(from)) {
                String merged = GeneratedNames.findMergedFrom(shape.visibleAnnotations);
                if (merged != null && !mixedMixins.contains(merged)) {
                    body.visibleAnnotations = GeneratedNames.dropMerged(shape.visibleAnnotations);
                }

                stubbed.add(shape.name + shape.desc);
                MethodNode inherited = Overrides.findInherited(running, shape, classBytes);
                if (shape.name.equals("<clinit>")) {
                    body.instructions.add(new InsnNode(Opcodes.RETURN));
                } else if (inherited != null) {
                    inherited.accept(new CodeOnly(body));
                    rehashes |= HASHING.contains(shape.name + shape.desc) && !Overrides.callsInherited(shape);
                } else if (BodyMarks.injectCallback(shape)) {
                    body.instructions.add(new InsnNode(Opcodes.RETURN));
                    BodyMarks.prependSkipNotice(body, running.name);
                } else if (HandlerFallbacks.passThrough(body, shape)
                        || HandlerFallbacks.passThroughWrapped(body, shape)) {
                    BodyMarks.prependSkipNotice(body, running.name);
                } else {
                    shape.accept(new CodeOnly(body));
                    String mixin = GeneratedNames.findMergedFrom(shape.visibleAnnotations);
                    BodyMarks.prependReachNotice(body, running.name, mixin == null ? "" : mixin);
                }
            } else {
                from.accept(new CodeOnly(body));
                split.rewrite(body, false);
                if (body.name.equals("<init>") && !addedFields.isEmpty()) {
                    BodyMarks.markMade(body, running);
                }
                rehashes |= HASHING.contains(shape.name + shape.desc) && Overrides.callsInherited(shape)
                        && !Overrides.callsInherited(from);
            }
            bodies.add(body);
        }
        out.methods = bodies;

        if (out.recordComponents != null) {
            out.recordComponents.forEach(component -> {
                component.visibleTypeAnnotations = null;
                component.invisibleTypeAnnotations = null;
            });
        }

        ClassNode side = new ClassNode();
        side.visit(mixed.version, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, split.sidecar, null,
                "java/lang/Object", null);
        List<String> moved = new ArrayList<>();
        Set<String> movedInstance = new HashSet<>();
        for (MethodNode added : addedMethods.values()) {
            if ((added.access & Opcodes.ACC_STATIC) == 0) {
                movedInstance.add(added.name + split.makeStaticDescriptor(added));
            }
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | (added.access & (
                    Opcodes.ACC_SYNTHETIC | Opcodes.ACC_VARARGS)),
                    added.name, split.makeStaticDescriptor(added), null, added.exceptions.toArray(String[]::new));
            added.accept(new CodeOnly(method));
            split.rewrite(method, true);
            if ((added.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                split.holdLock(method, (added.access & Opcodes.ACC_STATIC) != 0);
            }
            side.methods.add(method);
            moved.add(added.name + added.desc);
        }
        Set<String> allMoved = new HashSet<>(Linker.getMoved());
        allMoved.addAll(batchMoved);
        Set<String> notMoved = new HashSet<>(declared);
        notMoved.addAll(addedMethods.keySet());
        DuckBridge.rewriteInheritedMoved(out, running.name, allMoved, notMoved);
        DuckBridge.rewriteInheritedMoved(side, running.name, allMoved, notMoved);
        AccessBridge.rewriteMembers(out, running.name, Linker.getOpenings());
        AccessBridge.rewriteMembers(side, running.name, Linker.getOpenings());
        DuckBridge.rewrite(out, allDucks, allMoved);
        DuckBridge.rewrite(side, allDucks, allMoved);
        ReflectionBridge.rewrite(out);
        ReflectionBridge.rewrite(side);

        if (HandlerGuards.ENABLED) {
            HandlerGuards.makeSites(running.name, out);
        }

        byte[] target = OldClasses.write(out, mixed.version, new ClassReader(runningBytes), ClassWriter.COMPUTE_MAXS,
                classBytes);
        return new Result(LambdaSites.rewrite(target),
                LambdaSites.rewrite(OldClasses.write(side, side.version, null, ClassWriter.COMPUTE_MAXS, classBytes)),
                List.copyOf(moved), List.copyOf(stubbed), Set.copyOf(ducks),
                holderFields.isEmpty() ? null : split.buildFieldHolder(mixed.version, holderFields, mixed, allDucks,
                        allMoved, classBytes),
                Set.copyOf(movedInstance), rehashes);
    }

    private byte[] buildFieldHolder(int version, List<FieldNode> fields, ClassNode mixed, Set<String> ducks,
            Set<String> moved, Function<String, byte @Nullable []> classBytes) throws SplitException {
        String target = this.target;
        String name = target + "$hotwire$fields";
        ClassNode holder = new ClassNode();
        holder.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, name, null,
                "java/lang/Object", null);
        Set<String> added = new HashSet<>();
        for (FieldNode field : fields) {
            int access = Opcodes.ACC_PUBLIC | (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE
                    | Opcodes.ACC_TRANSIENT));
            holder.fields.add(new FieldNode(access, field.name, field.desc, field.signature, null));
            if (!this.writtenFinals.contains(field.name + field.desc)) {
                added.add(field.name + field.desc);
            }
        }
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        holder.methods.add(init);
        MethodNode instance = FieldInitializers.buildInstanceInitializer(target, name, mixed, added);
        MethodNode statics = FieldInitializers.buildStaticInitializer(target, name, mixed, added);
        this.rewrite(instance, false);
        this.rewrite(statics, false);
        WrittenFinals.start(this.target, this.writtenFinals, fields, name, instance, statics);
        holder.methods.add(instance);
        holder.methods.add(statics);
        AccessBridge.rewriteMembers(holder, target, Linker.getOpenings());
        DuckBridge.rewrite(holder, ducks, moved);
        ReflectionBridge.rewrite(holder);
        DuckBridge.eraseFields(holder, ducks);
        return OldClasses.write(holder, holder.version, null, ClassWriter.COMPUTE_MAXS, classBytes);
    }

    private static Set<String> findStillFinal(ClassNode running) {
        Set<String> still = new HashSet<>();
        running.fields.forEach(field -> {
            if ((field.access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == Opcodes.ACC_FINAL) {
                still.add(field.name + field.desc);
            }
        });
        return still;
    }

    private void rewrite(MethodNode method, boolean inSidecar) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            switch (insn) {
                case FieldInsnNode field -> {
                    if (!field.owner.equals(this.target)
                            && this.addedElsewhere.apply(field.owner).contains(field.name + field.desc)) {
                        method.instructions.set(field, ForeignFields.makeAccess(field));
                    } else if (field.owner.equals(this.target) && this.addedFields.contains(field.name + field.desc)
                            && !(!inSidecar && this.isOwnInitialization(method, field))) {
                        method.instructions.set(field, this.makeFieldAccess(field));
                    } else if (!inSidecar && field.getOpcode() == Opcodes.PUTFIELD
                            && this.isOwnInitialization(method, field)) {
                        this.copyToHolder(method, field);
                    } else if (field.owner.equals(this.target) && field.getOpcode() == Opcodes.PUTSTATIC
                            && this.writtenInPlace.contains(field.name + field.desc)
                            && (inSidecar || !method.name.equals("<clinit>"))) {
                        method.instructions.set(field, new InvokeDynamicInsnNode(field.name, "(" + field.desc + ")V",
                                FIELD, Linker.PUT_FINAL, field.desc));
                    } else if (field.owner.equals(this.target) && field.getOpcode() == Opcodes.PUTFIELD
                            && this.stillFinal.contains(field.name + field.desc)
                            && (inSidecar || !method.name.equals("<init>"))) {
                        method.instructions.set(field, new InvokeDynamicInsnNode(field.name,
                                "(L" + this.target + ";" + field.desc + ")V", FIELD, Linker.PUT_FINAL, field.desc));
                    } else if (inSidecar && this.isReachedAsInherited(field.owner, field.name + field.desc)
                            && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.PUTFIELD)) {
                        boolean get = field.getOpcode() == Opcodes.GETFIELD;
                        String owner = "L" + field.owner + ";";
                        method.instructions.set(field, new InvokeDynamicInsnNode(field.name,
                                get ? "(" + owner + ")" + field.desc : "(" + owner + field.desc + ")V", INHERITED,
                                Type.getObjectType(field.owner), get ? Linker.GET_FIELD : Linker.PUT_FIELD));
                    }
                }
                case MethodInsnNode call -> {
                    if (inSidecar && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && this.isReachedAsInherited(call.owner, call.name + call.desc)
                            && !this.addedMethods.containsKey(call.name + call.desc)) {
                        method.instructions.set(call, new InvokeDynamicInsnNode(call.name,
                                "(L" + call.owner + ";" + call.desc.substring(1), INHERITED,
                                Type.getObjectType(call.owner), Linker.VIRTUAL));
                    } else if (call.owner.equals(this.target)) {
                        this.rewriteCall(method, call, inSidecar);
                    } else if (inSidecar && call.getOpcode() == Opcodes.INVOKESPECIAL && !call.name.equals("<init>")) {
                        String withReceiver = "(L" + this.target + ";" + call.desc.substring(1);
                        method.instructions.set(call, new InvokeDynamicInsnNode(call.name, withReceiver, SUPER,
                                Type.getObjectType(call.owner)));
                    }
                }
                case InvokeDynamicInsnNode indy -> {
                    this.rewriteLambda(method, indy);
                    if (inSidecar) {
                        for (int i = 0; i < indy.bsmArgs.length; i++) {
                            if (indy.bsmArgs[i] instanceof Handle handle) {
                                indy.bsmArgs[i] = this.makeNestmateHandle(handle);
                            }
                        }
                    }
                }
                case LdcInsnNode ldc when inSidecar && ldc.cst instanceof Handle handle -> {
                    ldc.cst = this.makeNestmateHandle(handle);
                }
                default -> {
                }
            }
        }
    }

    private void copyToHolder(MethodNode method, FieldInsnNode field) {
        Type type = Type.getType(field.desc);
        int local = method.maxLocals;
        method.maxLocals += type.getSize();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), local));
        before.add(new InsnNode(Opcodes.DUP));
        before.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
        method.instructions.insertBefore(field, before);
        InsnList after = new InsnList();
        after.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
        after.add(this.makeFieldAccess(new FieldInsnNode(Opcodes.PUTFIELD, field.owner, field.name, field.desc)));
        method.instructions.insert(field, after);
    }

    private boolean isOwnInitialization(MethodNode method, FieldInsnNode field) {
        return this.writtenFinals.contains(field.name + field.desc)
                && (field.getOpcode() == Opcodes.PUTFIELD ? method.name.equals("<init>")
                        : field.getOpcode() == Opcodes.PUTSTATIC && method.name.equals("<clinit>"));
    }

    private Handle makeNestmateHandle(Handle handle) {
        if (handle.getTag() == Opcodes.H_INVOKESPECIAL && handle.getOwner().equals(this.target)
                && !handle.getName().equals("<init>")) {
            return new Handle(Opcodes.H_INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(),
                    handle.isInterface());
        }
        return handle;
    }

    private void holdLock(MethodNode method, boolean isStatic) {
        InsnList lock = new InsnList();
        this.pushLock(lock, isStatic);
        lock.add(new InsnNode(Opcodes.MONITORENTER));
        LabelNode start = new LabelNode();
        lock.add(start);
        method.instructions.insert(lock);
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                InsnList release = new InsnList();
                this.pushLock(release, isStatic);
                release.add(new InsnNode(Opcodes.MONITOREXIT));
                method.instructions.insertBefore(insn, release);
            }
        }
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(end);
        method.instructions.add(handler);
        Object[] locals = isStatic ? new Object[0] : new Object[] {this.target};
        method.instructions.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 1,
                new Object[] {"java/lang/Throwable"}));
        this.pushLock(method.instructions, isStatic);
        method.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }

    private void pushLock(InsnList into, boolean isStatic) {
        into.add(isStatic ? new LdcInsnNode(Type.getObjectType(this.target)) : new VarInsnNode(Opcodes.ALOAD, 0));
    }

    private boolean isReachedAsInherited(String owner, String member) {
        return owner.equals(this.target) ? !this.declared.contains(member) : this.fromElsewhere(owner);
    }

    private boolean fromElsewhere(String owner) {
        return !owner.equals(this.target) && !getPackageOf(owner).equals(getPackageOf(this.target))
                && !owner.startsWith("java/") && !owner.startsWith("[");
    }

    private static String getPackageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private void rewriteCall(MethodNode method, MethodInsnNode call, boolean inSidecar) {
        MethodNode added = this.addedMethods.get(call.name + call.desc);
        if (added != null) {
            String descriptor = this.makeStaticDescriptor(added);
            method.instructions.set(call, inSidecar
                    ? new MethodInsnNode(Opcodes.INVOKESTATIC, this.sidecar, call.name, descriptor, false)
                    : new InvokeDynamicInsnNode(call.name, descriptor, METHOD));
        } else if (inSidecar && call.getOpcode() == Opcodes.INVOKESPECIAL && !call.name.equals("<init>")) {
            call.setOpcode(Opcodes.INVOKEVIRTUAL);
        }
    }

    private void rewriteLambda(MethodNode method, InvokeDynamicInsnNode indy) {
        if (!indy.bsm.getOwner().equals(METAFACTORY) || !indy.bsm.getName().equals("metafactory")
                || !(indy.bsmArgs[1] instanceof Handle impl) || !impl.getOwner().equals(this.target)) {
            return;
        }
        MethodNode added = this.addedMethods.get(impl.getName() + impl.getDesc());
        if (added != null) {
            method.instructions.set(indy, new InvokeDynamicInsnNode(indy.name, indy.desc, LAMBDA,
                    indy.bsmArgs[0], impl.getName(), this.makeStaticDescriptor(added), indy.bsmArgs[2]));
        }
    }

    private AbstractInsnNode makeFieldAccess(FieldInsnNode field) {
        String self = "L" + this.target + ";";
        return switch (field.getOpcode()) {
            case Opcodes.GETFIELD -> new InvokeDynamicInsnNode(field.name, "(" + self + ")" + field.desc, FIELD,
                    Linker.GET_FIELD, field.desc);
            case Opcodes.PUTFIELD -> new InvokeDynamicInsnNode(field.name, "(" + self + field.desc + ")V", FIELD,
                    Linker.PUT_FIELD, field.desc);
            case Opcodes.GETSTATIC -> new InvokeDynamicInsnNode(field.name, "()" + field.desc, FIELD,
                    Linker.GET_STATIC, field.desc);
            default -> new InvokeDynamicInsnNode(field.name, "(" + field.desc + ")V", FIELD,
                    Linker.PUT_STATIC, field.desc);
        };
    }

    private String makeStaticDescriptor(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0 ? method.desc : "(L" + this.target + ";"
                + method.desc.substring(1);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static final class CodeOnly extends MethodVisitor {
        CodeOnly(MethodVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visitParameter(String name, int access) {
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotationDefault() {
            return null;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return null;
        }

        @Override
        public @Nullable AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
            return null;
        }

        @Override
        public @Nullable AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            return null;
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
        }
    }
}
