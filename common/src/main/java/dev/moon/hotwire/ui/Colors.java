package dev.moon.hotwire.ui;

public final class Colors {
    private Colors() {
    }

    public static int fade(int argb, float alpha) {
        int a = (int) ((argb >>> 24) * clamp(alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int from, int to, float t) {
        float k = clamp(t);
        return mixChannel(from, to, 24, k) | mixChannel(from, to, 16, k) | mixChannel(from, to, 8,
                k) | mixChannel(from, to, 0, k);
    }

    public static int blend(int from, int to, float t) {
        return 0xFF000000 | (mix(from, to, t) & 0xFFFFFF);
    }

    private static int mixChannel(int from, int to, int shift, float t) {
        int a = (from >>> shift) & 0xFF;
        int b = (to >>> shift) & 0xFF;
        return ((int) (a + (b - a) * t) & 0xFF) << shift;
    }

    private static float clamp(float t) {
        return Math.max(0, Math.min(1, t));
    }
}
