package dev.moon.hotwire.fabric.report;

import com.sun.management.OperatingSystemMXBean;
import dev.moon.hotwire.fabric.LoadedMods;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;

final class MachineFacts {
    private static final long MIB = 1024L * 1024L;

    private MachineFacts() {
    }

    static List<String> read() {
        List<String> out = new ArrayList<>();
        out.add("Hotwire: " + findVersionOf("hotwire"));
        out.add("Minecraft: " + findVersionOf("minecraft"));
        out.add("Fabric Loader: " + findVersionOf("fabricloader"));
        out.add("Java: " + System.getProperty("java.version") + ", " + System.getProperty("java.vendor"));
        out.add("OS: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ") version "
                + System.getProperty("os.version"));
        String cpu = "unknown";
        String gpu = "unknown";
        try {
            HardwareAbstractionLayer hardware = new SystemInfo().getHardware();

            cpu = hardware.getProcessor().toString().lines().findFirst().orElse(cpu).strip();
            List<GraphicsCard> cards = hardware.getGraphicsCards();
            gpu = cards.isEmpty() ? gpu : cards.getFirst().getName().strip();
        } catch (RuntimeException | LinkageError e) {
        }
        out.add("CPU: " + cpu + " (" + Runtime.getRuntime().availableProcessors() + " threads)");
        out.add("GPU: " + gpu);
        long machine = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean
                ? bean.getTotalMemorySize() / MIB : 0;
        out.add("Memory: " + Runtime.getRuntime().maxMemory() / MIB + " MiB for the game"
                + (machine > 0 ? ", " + machine + " MiB in the machine" : ""));
        return out;
    }

    private static String findVersionOf(String id) {
        return LoadedMods.findVersion(id, "unknown");
    }
}
