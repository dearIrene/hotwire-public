package dev.moon.hotwire.fabric.report;

import com.sun.management.OperatingSystemMXBean;
import dev.moon.hotwire.fabric.LoadedMods;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;

final class MachineDetails {
    private static final long MIB = 1024L * 1024L;

    private MachineDetails() {
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
            gpu = cards.isEmpty() ? gpu
                    : cards.stream().map(card -> card.getName().strip()).collect(Collectors.joining(", "));
        } catch (RuntimeException | LinkageError e) {
        }
        out.add("CPU: " + cpu + " (" + Runtime.getRuntime().availableProcessors() + " threads)");
        out.add("GPUs: " + gpu);
        long machine = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean
                ? bean.getTotalMemorySize() / MIB : 0;
        out.add("Memory: " + Runtime.getRuntime().maxMemory() / MIB + " MiB for the game"
                + (machine > 0 ? ", " + machine + " MiB in the machine" : "") + ", "
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / MIB + " MiB in use now");

        out.add("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + ", "
                + ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName)
                .collect(Collectors.joining(", ")));
        out.add("JVM switches: " + ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-X")).collect(Collectors.joining(" ")));
        return out;
    }

    private static String findVersionOf(String id) {
        return LoadedMods.findVersion(id, "unknown");
    }
}
