package dev.moon.hotwire.dev;

import java.util.List;

public record DevCompare(int a, int b, int on, double fpsA, double fpsB, double tickMillisA, double tickMillisB,
        double allocMbA, double allocMbB, double secondsA, double secondsB, boolean done, String failed,
        List<DevWhy> why) {
    public DevCompare {
        why = List.copyOf(why);
    }

    public static double findChange(double a, double b) {
        return a == 0 ? 0 : (b - a) / a;
    }

    public int findOther() {
        return this.on == this.a ? this.b : this.a;
    }
}
