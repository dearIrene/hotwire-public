package dev.moon.hotwire.redefine;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.ObjectName;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeapCensus {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeapCensus.class);
    private static final String COMMAND = "com.sun.management:type=DiagnosticCommand";
    private static final Pattern ROW = Pattern.compile("\\s*\\d+:\\s+(\\d+)\\s+\\d+\\s+(\\S+).*");
    private static volatile @Nullable Map<String, Long> counts;

    private static volatile Class<?> @Nullable [] loaded;
    private static volatile boolean unavailableSaid;

    private HeapCensus() {
    }

    public static void forget() {
        counts = null;
        loaded = null;
    }

    public static boolean hasObjectsOf(Class<?> type, Supplier<Class<?>[]> classes) {
        Map<String, Long> now = read();
        if (now == null || now.getOrDefault(type.getName(), 0L) > 0) {
            return true;
        }
        Class<?>[] all = loaded;
        if (all == null) {
            all = classes.get();
            loaded = all;
        }
        for (Class<?> one : all) {
            if (one != type && type.isAssignableFrom(one) && now.getOrDefault(one.getName(), 0L) > 0) {
                return true;
            }
        }
        return false;
    }

    private static synchronized @Nullable Map<String, Long> read() {
        Map<String, Long> now = counts;
        if (now != null) {
            return now;
        }
        long began = System.nanoTime();
        try {
            Object text = ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName(COMMAND),
                    "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
            now = parse(String.valueOf(text));
            LOGGER.info("[Hotwire] the JVM counted the objects of {} classes in {} ms", now.size(),
                    (System.nanoTime() - began) / 1_000_000);
            counts = now;
            return now;
        } catch (JMException | RuntimeException | LinkageError e) {
            if (!unavailableSaid) {
                unavailableSaid = true;
                LOGGER.info("[Hotwire] the JVM does not count objects here: {}", e.toString());
            }
            return null;
        }
    }

    static Map<String, Long> parse(String histogram) {
        Map<String, Long> out = new HashMap<>();
        histogram.lines().forEach(line -> {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                out.put(row.group(2), Long.parseLong(row.group(1)));
            }
        });
        return out;
    }
}
