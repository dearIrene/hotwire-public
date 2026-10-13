package dev.moon.hotwire.ui;

import org.jspecify.annotations.Nullable;

public final class IconPixels {
    static final int MAX_SIZE = 256;

    private final int width;
    private final int height;
    private final int[] argb;

    private IconPixels(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    public static @Nullable IconPixels of(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0 || argb.length < width * height) {
            return null;
        }
        int block = block(width, height, argb);
        IconPixels retained = block == 1 ? new IconPixels(width, height, argb.clone())
                : reduce(width, height, argb, block);
        int side = Math.max(retained.width, retained.height);
        return side <= MAX_SIZE ? retained
                : retained.scale(fit(retained.width, side, MAX_SIZE), fit(retained.height, side, MAX_SIZE));
    }

    int getWidth() {
        return this.width;
    }

    int getHeight() {
        return this.height;
    }

    public int[] makeSquare(int size) {
        int side = Math.max(this.width, this.height);
        int width = fit(this.width, side, size);
        int height = fit(this.height, side, size);
        IconPixels fitted = this.scale(width, height);
        int[] out = new int[size * size];
        int left = (size - width) / 2;
        int top = (size - height) / 2;
        for (int y = 0; y < height; y++) {
            System.arraycopy(fitted.argb, y * width, out, (top + y) * size + left, width);
        }
        return out;
    }

    private static int fit(int length, int side, int size) {
        return Math.max(1, Math.round((float) length * size / side));
    }

    private static int block(int width, int height, int[] argb) {
        for (int size = computeGcd(width, height); size > 1; size--) {
            if (width % size == 0 && height % size == 0 && isEven(width, height, argb, size)) {
                return size;
            }
        }
        return 1;
    }

    private static boolean isEven(int width, int height, int[] argb, int size) {
        for (int y = 0; y < height; y++) {
            int corner = (y - y % size) * width;
            for (int x = 0; x < width; x++) {
                if (toVisible(argb[y * width + x]) != toVisible(argb[corner + x - x % size])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int toVisible(int argb) {
        return argb >>> 24 == 0 ? 0 : argb;
    }

    private static IconPixels reduce(int width, int height, int[] argb, int block) {
        int[] out = new int[(width / block) * (height / block)];
        for (int y = 0; y < height / block; y++) {
            for (int x = 0; x < width / block; x++) {
                out[y * (width / block) + x] = argb[y * block * width + x * block];
            }
        }
        return new IconPixels(width / block, height / block, out);
    }

    private static int computeGcd(int a, int b) {
        return b == 0 ? a : computeGcd(b, a % b);
    }

    private IconPixels scale(int toWidth, int toHeight) {
        if (toWidth == this.width && toHeight == this.height) {
            return this;
        }

        float[] from = new float[this.width * this.height * 4];
        for (int i = 0; i < this.width * this.height; i++) {
            int pixel = this.argb[i];
            float alpha = (pixel >>> 24) / 255f;
            from[i * 4] = alpha;
            from[i * 4 + 1] = ((pixel >>> 16) & 0xFF) * alpha;
            from[i * 4 + 2] = ((pixel >>> 8) & 0xFF) * alpha;
            from[i * 4 + 3] = (pixel & 0xFF) * alpha;
        }
        float[] across = pass(from, this.width, this.height, toWidth, true);
        float[] both = pass(across, toWidth, this.height, toHeight, false);
        int[] out = new int[toWidth * toHeight];
        for (int i = 0; i < out.length; i++) {
            float alpha = both[i * 4];
            if (alpha * 255 < 0.5f) {
                continue;
            }
            out[i] = (toChannel(alpha * 255) << 24) | (toChannel(both[i * 4 + 1] / alpha) << 16)
                    | (toChannel(both[i * 4 + 2] / alpha) << 8) | toChannel(both[i * 4 + 3] / alpha);
        }
        return new IconPixels(toWidth, toHeight, out);
    }

    private static float[] pass(float[] from, int width, int height, int to, boolean across) {
        int length = across ? width : height;
        int lines = across ? height : width;
        float[] out = new float[(across ? to * height : width * to) * 4];
        double step = (double) length / to;
        for (int i = 0; i < to; i++) {
            double start = i * step;
            double end = start + step;
            for (int j = (int) start; j < end && j < length; j++) {
                float weight = (float) ((Math.min(end, j + 1) - Math.max(start, j)) / step);
                if (weight <= 0) {
                    continue;
                }
                for (int line = 0; line < lines; line++) {
                    int source = (across ? line * width + j : j * width + line) * 4;
                    int target = (across ? line * to + i : i * width + line) * 4;
                    for (int c = 0; c < 4; c++) {
                        out[target + c] += from[source + c] * weight;
                    }
                }
            }
        }
        return out;
    }

    private static int toChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
