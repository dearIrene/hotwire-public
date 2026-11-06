package dev.moon.hotwire.config;

import java.lang.management.ManagementFactory;
import javax.management.JMException;
import javax.management.JMRuntimeException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

public record DevSetup(boolean inSetup, Jvm jvm) {
    public enum Jvm { FULL, JETBRAINS_WITHOUT_FLAG, OTHER }

    public static final DevSetup NONE = new DevSetup(false, Jvm.OTHER);
    private static final String FLAG = "AllowEnhancedClassRedefinition";
    private static final String DIAGNOSTIC = "com.sun.management:type=" + "HotSpot" + "Diagnostic";

    public static DevSetup find(boolean inSetup) {
        return new DevSetup(inSetup, inSetup ? findJvm() : Jvm.OTHER);
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
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+" + FLAG)) {
            return true;
        }
        try {
            Object option = ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName(DIAGNOSTIC),
                    "getVMOption", new Object[] {FLAG}, new String[] {String.class.getName()});
            return option instanceof CompositeData data && "true".equals(String.valueOf(data.get("value")));
        } catch (JMException | JMRuntimeException | SecurityException e) {
            return false;
        }
    }
}
