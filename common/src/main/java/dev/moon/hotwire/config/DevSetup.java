package dev.moon.hotwire.config;

import java.lang.management.ManagementFactory;

public record DevSetup(boolean inSetup, Jvm jvm) {
    public enum Jvm { FULL, JETBRAINS_WITHOUT_FLAG, OTHER }

    public static final DevSetup NONE = new DevSetup(false, Jvm.OTHER);
    private static final String FLAG = "AllowEnhancedClassRedefinition";

    public static DevSetup find(boolean inSetup) {
        return new DevSetup(inSetup, findJvm());
    }

    public boolean isFull() {
        return this.jvm == Jvm.FULL;
    }

    static Jvm findJvm() {
        if (isFlagOn()) {
            return Jvm.FULL;
        }
        String vendor = System.getProperty("java.vendor", "") + " " + System.getProperty("java.vendor.version", "");
        return vendor.contains("JetBrains") || vendor.contains("JBR") ? Jvm.JETBRAINS_WITHOUT_FLAG : Jvm.OTHER;
    }

    private static boolean isFlagOn() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+" + FLAG);
        } catch (SecurityException e) {
            return false;
        }
    }
}
