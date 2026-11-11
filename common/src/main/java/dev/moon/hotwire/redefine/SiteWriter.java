package dev.moon.hotwire.redefine;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;

final class SiteWriter {
    static final byte[] NO_CODE = new byte[0];

    private static final int HIT_LENGTH = 9;
    private static final int GATE_LENGTH = 12;

    private static final int ALIGN = 4;
    private static final int MOST_CODE = 65_535;

    private static final int MOST_GROWTH = 32;

    private static final int MOST_POOL_GROWTH = 512;
    private static final byte[] HOLDER_PREFIX = TripwireSites.HOLDER_PREFIX.getBytes(StandardCharsets.ISO_8859_1);
    private static final int WIDE = 196;
    private static final byte[] LENGTHS = makeLengths();

    private final byte[] in;
    private final String mod;
    private byte[] out = NO_CODE;
    private int to;
    private int[] pool = new int[0];

    private int codeName;
    private int framesName;
    private int linesName;
    private int localsName;
    private int localTypesName;
    private int voidType;
    private int booleanType;
    private boolean holdsSites;

    private int offField;
    private int hitSite;
    private int gateSite;

    private int moved;

    private SiteWriter(byte[] in, String mod) {
        this.in = in;
        this.mod = mod;
    }

    static byte @Nullable [] arm(byte[] bytes, String mod) {
        try {
            return new SiteWriter(bytes, mod).write();
        } catch (IndexOutOfBoundsException | IllegalStateException e) {
            return null;
        }
    }

    private byte @Nullable [] write() {
        OptionalInt pool = this.readPool();
        if (pool.isEmpty() || this.holdsSites) {
            return null;
        }
        int poolEnd = pool.getAsInt();
        int methodsAt = this.skipFields(poolEnd + 6 + 2 + 2 * this.readShort(poolEnd + 6));
        int methods = this.readShort(methodsAt);

        int[] sites = new int[methods];
        byte[] gates = new byte[methods];
        OptionalInt planned = this.planMethods(methodsAt + 2, sites, gates);
        if (planned.isEmpty()) {
            return null;
        }
        int attributesAt = planned.getAsInt();
        boolean anyHit = false;
        boolean anyGate = false;
        for (int method = 0; method < methods; method++) {
            anyHit |= sites[method] > 0 && gates[method] == 0;
            anyGate |= gates[method] != 0;
        }
        if (!anyHit && !anyGate) {
            return NO_CODE;
        }
        this.out = new byte[this.in.length + MOST_POOL_GROWTH + methods * MOST_GROWTH];
        System.arraycopy(this.in, 0, this.out, 0, poolEnd);
        this.to = poolEnd;
        int added = this.writePool(TripwireSites.findHolder(this.mod), anyHit, anyGate);
        if (added > MOST_CODE) {
            return null;
        }
        this.out[8] = (byte) (added >>> 8);
        this.out[9] = (byte) added;
        this.copy(poolEnd, methodsAt + 2 - poolEnd);
        this.writeMethods(methodsAt + 2, sites, gates);
        this.copy(attributesAt, this.in.length - attributesAt);
        return Arrays.copyOf(this.out, this.to);
    }

    private OptionalInt readPool() {
        int count = this.readShort(8);
        this.pool = new int[count];
        int at = 10;
        int entry = 1;
        while (entry < count) {
            this.pool[entry] = at;
            switch (this.in[at]) {
                case 1 -> {
                    int length = this.readShort(at + 1);
                    this.noteName(entry, at + 3, length);
                    at += 3 + length;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5;
                case 5, 6 -> {
                    at += 9;

                    entry++;
                }
                case 7, 8, 16, 19, 20 -> at += 3;
                case 15 -> at += 4;
                default -> {
                    return OptionalInt.empty();
                }
            }
            entry++;
        }
        return OptionalInt.of(at);
    }

    private void noteName(int entry, int at, int length) {
        switch (length) {
            case 3 -> {
                if (this.isText(at, length, "()V")) {
                    this.voidType = entry;
                } else if (this.isText(at, length, "()Z")) {
                    this.booleanType = entry;
                }
            }
            case 4 -> {
                if (this.isText(at, length, "Code")) {
                    this.codeName = entry;
                }
            }
            case 13 -> {
                if (this.isText(at, length, "StackMapTable")) {
                    this.framesName = entry;
                }
            }
            case 15 -> {
                if (this.isText(at, length, "LineNumberTable")) {
                    this.linesName = entry;
                }
            }
            case 18 -> {
                if (this.isText(at, length, "LocalVariableTable")) {
                    this.localsName = entry;
                }
            }
            case 22 -> {
                if (this.isText(at, length, "LocalVariableTypeTable")) {
                    this.localTypesName = entry;
                }
            }
            default -> { }
        }

        if (length > HOLDER_PREFIX.length
                && Arrays.equals(this.in, at, at + HOLDER_PREFIX.length, HOLDER_PREFIX, 0, HOLDER_PREFIX.length)) {
            this.holdsSites = true;
        }
    }

    private boolean isText(int at, int length, String text) {
        for (int i = 0; i < length; i++) {
            if (this.in[at + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int skipFields(int at) {
        int fields = this.readShort(at);
        int place = at + 2;
        for (int field = 0; field < fields; field++) {
            place = this.skipAttributes(place + 6);
        }
        return place;
    }

    private int skipAttributes(int at) {
        int attributes = this.readShort(at);
        int place = at + 2;
        for (int attribute = 0; attribute < attributes; attribute++) {
            place += 6 + this.readInt(place + 2);
        }
        return place;
    }

    private OptionalInt planMethods(int first, int[] sites, byte[] gates) {
        int at = first;
        for (int method = 0; method < sites.length; method++) {
            int name = this.readShort(at + 2);
            int desc = this.readShort(at + 4);
            int attributes = this.readShort(at + 6);
            at += 8;
            for (int attribute = 0; attribute < attributes; attribute++) {
                if (this.codeName != 0 && this.readShort(at) == this.codeName
                        && !this.planCode(at, name, desc, sites, gates, method)) {
                    return OptionalInt.empty();
                }
                at += 6 + this.readInt(at + 2);
            }
        }
        return OptionalInt.of(at);
    }

    private boolean planCode(int at, int name, int desc, int[] sites, byte[] gates, int method) {
        int length = this.readInt(at + 10);
        int code = at + 14;
        int exceptions = this.readShort(code + length);
        int attributesAt = code + length + 2 + 8 * exceptions;
        int attributes = this.readShort(attributesAt);
        int place = attributesAt + 2;
        for (int attribute = 0; attribute < attributes; attribute++) {
            int kind = this.readShort(place);
            if (kind != this.linesName && kind != this.localsName && kind != this.localTypesName
                    && kind != this.framesName) {
                return false;
            }
            place += 6 + this.readInt(place + 2);
        }
        String nameText = this.readText(name);
        String descText = this.readText(desc);
        int site = HIT_LENGTH;
        if (TripwireSites.isGated(nameText, descText)) {
            byte returned = (byte) descText.charAt(descText.indexOf(')') + 1);
            gates[method] = returned;
            site = GATE_LENGTH + (returned == 'V' ? 1 : 2);
        }
        if (this.hasSwitch(code, length)) {
            site = (site + ALIGN - 1) / ALIGN * ALIGN;
        }
        sites[method] = site;
        return length + site <= MOST_CODE;
    }

    private String readText(int entry) {
        int at = this.pool[entry];
        return new String(this.in, at + 3, this.readShort(at + 1), StandardCharsets.ISO_8859_1);
    }

    private boolean hasSwitch(int code, int length) {
        int at = code;
        int end = code + length;
        while (at < end) {
            int op = this.in[at] & 0xFF;
            if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) {
                return true;
            }
            at += op == WIDE ? (this.in[at + 1] & 0xFF) == Opcodes.IINC ? 6 : 4 : findLength(op);
        }
        return false;
    }

    private static int findLength(int op) {
        int length = LENGTHS[op];
        if (length == 0) {
            throw new IllegalStateException("no such instruction: " + op);
        }
        return length;
    }

    private static byte[] makeLengths() {
        byte[] lengths = new byte[256];
        Arrays.fill(lengths, 0, 202, (byte) 1);
        for (int op : new int[] {Opcodes.BIPUSH, Opcodes.LDC, Opcodes.NEWARRAY, Opcodes.RET}) {
            lengths[op] = 2;
        }
        Arrays.fill(lengths, Opcodes.ILOAD, Opcodes.ALOAD + 1, (byte) 2);
        Arrays.fill(lengths, Opcodes.ISTORE, Opcodes.ASTORE + 1, (byte) 2);
        for (int op : new int[] {Opcodes.SIPUSH, 19, 20, Opcodes.IINC, Opcodes.NEW, Opcodes.ANEWARRAY,
                Opcodes.CHECKCAST, Opcodes.INSTANCEOF, Opcodes.IFNULL, Opcodes.IFNONNULL}) {
            lengths[op] = 3;
        }
        Arrays.fill(lengths, Opcodes.IFEQ, Opcodes.JSR + 1, (byte) 3);
        Arrays.fill(lengths, Opcodes.GETSTATIC, Opcodes.INVOKESTATIC + 1, (byte) 3);
        lengths[Opcodes.MULTIANEWARRAY] = 4;
        lengths[Opcodes.INVOKEINTERFACE] = 5;
        lengths[Opcodes.INVOKEDYNAMIC] = 5;
        lengths[200] = 5;
        lengths[201] = 5;
        lengths[Opcodes.TABLESWITCH] = 0;
        lengths[Opcodes.LOOKUPSWITCH] = 0;
        lengths[WIDE] = 0;
        return lengths;
    }

    private int writePool(String holder, boolean anyHit, boolean anyGate) {
        int next = this.pool.length;
        this.putText(holder);
        int holderName = next++;
        this.writeByte(7);
        this.writeShort(holderName);
        int type = next++;
        this.putText(TripwireSites.OFF);
        int name = next++;
        this.putText("Z");
        int desc = next++;
        this.writeByte(12);
        this.writeShort(name);
        this.writeShort(desc);
        int nameAndType = next++;
        this.writeByte(9);
        this.writeShort(type);
        this.writeShort(nameAndType);
        this.offField = next++;
        if (anyHit) {
            next = this.putCall(next, type, TripwireSites.HIT, "()V", this.voidType);
            this.hitSite = next - 1;
        }
        if (anyGate) {
            next = this.putCall(next, type, TripwireSites.SKIP, "()Z", this.booleanType);
            this.gateSite = next - 1;
        }
        if (this.framesName == 0) {
            this.putText("StackMapTable");
            this.framesName = next++;
        }
        return next;
    }

    private int putCall(int first, int type, String name, String desc, int known) {
        int next = first;
        this.putText(name);
        int nameEntry = next++;
        int descEntry = known;
        if (known == 0) {
            this.putText(desc);
            descEntry = next++;
        }
        this.writeByte(12);
        this.writeShort(nameEntry);
        this.writeShort(descEntry);
        int nameAndType = next++;
        this.writeByte(10);
        this.writeShort(type);
        this.writeShort(nameAndType);
        return next + 1;
    }

    private void putText(String text) {
        this.writeByte(1);
        this.writeShort(text.length());
        for (int i = 0; i < text.length(); i++) {
            this.out[this.to++] = (byte) text.charAt(i);
        }
    }

    private void writeMethods(int first, int[] sites, byte[] gates) {
        int at = first;
        for (int method = 0; method < sites.length; method++) {
            this.copy(at, 8);
            int attributes = this.readShort(at + 6);
            at += 8;
            for (int attribute = 0; attribute < attributes; attribute++) {
                int length = 6 + this.readInt(at + 2);
                if (this.codeName != 0 && this.readShort(at) == this.codeName) {
                    this.moved = sites[method];
                    this.writeCode(at, gates[method]);
                } else {
                    this.copy(at, length);
                }
                at += length;
            }
        }
    }

    private void writeCode(int at, byte returned) {
        int length = this.readInt(at + 10);
        this.copy(at, 2);
        int lengthAt = this.to;
        this.to += 4;
        int stack = this.readShort(at + 6);
        this.writeShort(Math.max(stack, returned == 'J' || returned == 'D' ? 2 : 1));
        this.copy(at + 8, 2);
        this.writeInt(length + this.moved);
        this.writeSite(returned);
        this.copy(at + 14, length);
        int exceptionsAt = at + 14 + length;
        int exceptions = this.readShort(exceptionsAt);
        this.writeShort(exceptions);
        for (int exception = 0; exception < exceptions; exception++) {
            int entry = exceptionsAt + 2 + 8 * exception;
            this.writeShort(this.readShort(entry) + this.moved);
            this.writeShort(this.readShort(entry + 2) + this.moved);
            this.writeShort(this.readShort(entry + 4) + this.moved);
            this.copy(entry + 6, 2);
        }
        int attributesAt = exceptionsAt + 2 + 8 * exceptions;
        int attributes = this.readShort(attributesAt);
        int countAt = this.to;
        this.writeShort(attributes);
        boolean framed = false;
        int place = attributesAt + 2;
        for (int attribute = 0; attribute < attributes; attribute++) {
            int kind = this.readShort(place);
            if (kind == this.framesName) {
                this.writeFrames(place);
                framed = true;
            } else if (kind == this.linesName) {
                this.writeTable(place, 4);
            } else {
                this.writeTable(place, 10);
            }
            place += 6 + this.readInt(place + 2);
        }
        if (!framed) {
            this.writeShort(this.framesName);
            this.writeInt(3);
            this.writeShort(1);
            this.writeByte(this.moved);
            this.out[countAt] = (byte) ((attributes + 1) >>> 8);
            this.out[countAt + 1] = (byte) (attributes + 1);
        }
        this.patchInt(lengthAt, this.to - lengthAt - 4);
    }

    private void writeSite(byte returned) {
        this.writeByte(Opcodes.GETSTATIC);
        this.writeShort(this.offField);
        this.writeByte(Opcodes.IFEQ);
        this.writeShort(this.moved - 3);
        this.writeByte(Opcodes.INVOKESTATIC);
        if (returned == 0) {
            this.writeShort(this.hitSite);
            this.fillNops(this.moved - HIT_LENGTH);
            return;
        }
        this.writeShort(this.gateSite);
        int ending = returned == 'V' ? 1 : 2;
        this.fillNops(this.moved - GATE_LENGTH - ending);
        this.writeByte(Opcodes.IFEQ);
        this.writeShort(3 + ending);
        switch (returned) {
            case 'V' -> this.writeByte(Opcodes.RETURN);
            case 'J' -> {
                this.writeByte(Opcodes.LCONST_0);
                this.writeByte(Opcodes.LRETURN);
            }
            case 'F' -> {
                this.writeByte(Opcodes.FCONST_0);
                this.writeByte(Opcodes.FRETURN);
            }
            case 'D' -> {
                this.writeByte(Opcodes.DCONST_0);
                this.writeByte(Opcodes.DRETURN);
            }
            case 'L', '[' -> {
                this.writeByte(Opcodes.ACONST_NULL);
                this.writeByte(Opcodes.ARETURN);
            }
            default -> {
                this.writeByte(Opcodes.ICONST_0);
                this.writeByte(Opcodes.IRETURN);
            }
        }
    }

    private void fillNops(int count) {
        for (int i = 0; i < count; i++) {
            this.writeByte(Opcodes.NOP);
        }
    }

    private void writeTable(int at, int entryLength) {
        this.copy(at, 8);
        int entries = this.readShort(at + 6);
        for (int entry = 0; entry < entries; entry++) {
            int place = at + 8 + entry * entryLength;
            this.writeShort(this.readShort(place) + this.moved);
            this.copy(place + 2, entryLength - 2);
        }
    }

    private void writeFrames(int at) {
        this.copy(at, 2);
        int lengthAt = this.to;
        this.to += 4;
        int entries = this.readShort(at + 6);
        int place = at + 8;
        if (entries == 0) {
            this.writeShort(1);
            this.writeByte(this.moved);
        } else {
            int first = this.findDelta(place);
            boolean ownFrame = first != 0;
            this.writeShort(entries + (ownFrame ? 1 : 0));
            if (ownFrame) {
                this.writeByte(this.moved);
            }
            place = this.writeFrame(place, ownFrame ? first - 1 : this.moved);
            for (int entry = 1; entry < entries; entry++) {
                place = this.writeFrame(place, this.findDelta(place));
            }
        }
        this.patchInt(lengthAt, this.to - lengthAt - 4);
    }

    private int findDelta(int at) {
        int type = this.in[at] & 0xFF;
        if (type < 64) {
            return type;
        }
        return type < 128 ? type - 64 : this.readShort(at + 1);
    }

    private int writeFrame(int at, int delta) {
        int type = this.in[at] & 0xFF;
        if (type < 64 || type == 251) {
            this.putDelta(delta, 0, 251);
            return at + (type < 64 ? 1 : 3);
        }
        if (type < 128 || type == 247) {
            this.putDelta(delta, 64, 247);
            return this.copyType(at + (type < 128 ? 1 : 3));
        }
        if (type < 247) {
            throw new IllegalStateException("no such frame: " + type);
        }
        this.writeByte(type);
        this.writeShort(delta);
        int place = at + 3;
        if (type < 251) {
            return place;
        }
        if (type < 255) {
            for (int local = 251; local < type; local++) {
                place = this.copyType(place);
            }
            return place;
        }
        for (int list = 0; list < 2; list++) {
            int types = this.readShort(place);
            this.writeShort(types);
            place += 2;
            for (int one = 0; one < types; one++) {
                place = this.copyType(place);
            }
        }
        return place;
    }

    private void putDelta(int delta, int base, int wide) {
        if (delta < 64) {
            this.writeByte(base + delta);
        } else {
            this.writeByte(wide);
            this.writeShort(delta);
        }
    }

    private int copyType(int at) {
        int tag = this.in[at];
        this.writeByte(tag);
        if (tag == 7) {
            this.copy(at + 1, 2);
            return at + 3;
        }
        if (tag == 8) {
            this.writeShort(this.readShort(at + 1) + this.moved);
            return at + 3;
        }
        return at + 1;
    }

    private void copy(int at, int length) {
        System.arraycopy(this.in, at, this.out, this.to, length);
        this.to += length;
    }

    private void writeByte(int value) {
        this.out[this.to++] = (byte) value;
    }

    private void writeShort(int value) {
        this.out[this.to++] = (byte) (value >>> 8);
        this.out[this.to++] = (byte) value;
    }

    private void writeInt(int value) {
        this.patchInt(this.to, value);
        this.to += 4;
    }

    private void patchInt(int at, int value) {
        this.out[at] = (byte) (value >>> 24);
        this.out[at + 1] = (byte) (value >>> 16);
        this.out[at + 2] = (byte) (value >>> 8);
        this.out[at + 3] = (byte) value;
    }

    private int readShort(int at) {
        return (this.in[at] & 0xFF) << 8 | (this.in[at + 1] & 0xFF);
    }

    private int readInt(int at) {
        return this.readShort(at) << 16 | this.readShort(at + 2);
    }
}
