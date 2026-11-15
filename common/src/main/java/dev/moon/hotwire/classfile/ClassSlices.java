package dev.moon.hotwire.classfile;

import org.jspecify.annotations.Nullable;

public final class ClassSlices {
    private static final int POOL_START = 10;
    private final int poolCount;
    private final int poolEnd;
    private final int methodsAt;

    private final int[] methods;
    private final int end;

    private ClassSlices(int poolCount, int poolEnd, int methodsAt, int[] methods, int end) {
        this.poolCount = poolCount;
        this.poolEnd = poolEnd;
        this.methodsAt = methodsAt;
        this.methods = methods;
        this.end = end;
    }

    public static ClassSlices read(byte[] bytes) {
        try {
            int poolCount = readShort(bytes, 8);
            int at = POOL_START;
            for (int index = 1; index < poolCount; index++) {
                int tag = bytes[at];
                switch (tag) {
                    case 1 -> at += 3 + readShort(bytes, at + 1);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5;
                    case 5, 6 -> {
                        at += 9;
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> at += 3;
                    case 15 -> at += 4;
                    default -> throw new IllegalArgumentException("constant tag " + tag);
                }
            }
            int poolEnd = at;
            at += 6;
            at += 2 + 2 * readShort(bytes, at);
            at = skipMembers(bytes, at, null);
            int methodsAt = at;
            int[] methods = new int[readShort(bytes, at) + 1];
            at = skipMembers(bytes, at, methods);
            if (at > bytes.length) {
                throw new IllegalArgumentException("a class file cut short");
            }
            return new ClassSlices(poolCount, poolEnd, methodsAt, methods, bytes.length);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("a class file cut short", e);
        }
    }

    private static int skipMembers(byte[] bytes, int from, int @Nullable [] starts) {
        int count = readShort(bytes, from);
        int at = from + 2;
        for (int member = 0; member < count; member++) {
            if (starts != null) {
                starts[member] = at;
            }
            int attributes = readShort(bytes, at + 6);
            at += 8;
            for (int attribute = 0; attribute < attributes; attribute++) {
                at += 6 + readInt(bytes, at + 2);
            }
        }
        if (starts != null) {
            starts[count] = at;
        }
        return at;
    }

    private static int readShort(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 8 | (bytes[at + 1] & 0xFF);
    }

    private static int readInt(byte[] bytes, int at) {
        return readShort(bytes, at) << 16 | readShort(bytes, at + 2);
    }

    public int getPoolCount() {
        return this.poolCount;
    }

    public int getPoolStart() {
        return POOL_START;
    }

    public int getPoolEnd() {
        return this.poolEnd;
    }

    public int getMethodsAt() {
        return this.methodsAt;
    }

    public int countMethods() {
        return this.methods.length - 1;
    }

    public int getMethodStart(int method) {
        return this.methods[method];
    }

    public int getMethodEnd(int method) {
        return this.methods[method + 1];
    }

    public int getMethodsEnd() {
        return this.methods[this.methods.length - 1];
    }

    public int getEnd() {
        return this.end;
    }
}
