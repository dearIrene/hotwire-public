package dev.moon.hotwire.redefine;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

final class NullArguments {
    private final String owner;
    private final Map<MethodNode, Frame<SourceValue> @Nullable []> frames = new HashMap<>();

    NullArguments(String owner) {
        this.owner = owner;
    }

    boolean isNull(MethodNode method, MethodInsnNode call, int argument) {
        Frame<SourceValue>[] all = this.frames.computeIfAbsent(method, this::findFrames);
        int at = method.instructions.indexOf(call);
        Frame<SourceValue> frame = all == null || at < 0 || at >= all.length ? null : all[at];
        if (frame == null) {
            return false;
        }
        int count = Type.getArgumentTypes(call.desc).length;
        int slot = frame.getStackSize() - count + argument;
        if (slot < 0 || slot >= frame.getStackSize()) {
            return false;
        }
        SourceValue value = frame.getStack(slot);
        if (value.insns.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode source : value.insns) {
            if (source.getOpcode() != Opcodes.ACONST_NULL) {
                return false;
            }
        }
        return true;
    }

    private Frame<SourceValue> @Nullable [] findFrames(MethodNode method) {
        try {
            return new Analyzer<>(new SourceInterpreter()).analyze(this.owner, method);
        } catch (AnalyzerException | RuntimeException e) {
            return null;
        }
    }
}
