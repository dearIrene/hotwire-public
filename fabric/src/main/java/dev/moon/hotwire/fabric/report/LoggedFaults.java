package dev.moon.hotwire.fabric.report;

import java.util.function.BiConsumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

public final class LoggedFaults extends AbstractFilter {
    private static final String OWN = "dev.moon.hotwire.";

    private static final String HARNESS = "dev.moon.hotwire.fabric.harness.";
    private static final String PREFIX = "[Hotwire] ";

    private static volatile BiConsumer<String, Throwable> ownThrow = (said, thrown) -> { };

    private LoggedFaults() {
        super(Result.NEUTRAL, Result.NEUTRAL);
    }

    public static void install() {
        if (LogManager.getContext(false) instanceof LoggerContext context) {
            LoggedFaults filter = new LoggedFaults();
            filter.start();
            context.getConfiguration().addFilter(filter);
        } else {
            LoggerFactory.getLogger(LoggedFaults.class).error("[Hotwire] the game's logging is not the kind a filter"
                    + " goes on, so what a switch only logs is not among its faults");
        }
    }

    public static void onOwnThrow(BiConsumer<String, Throwable> heard) {
        ownThrow = heard;
    }

    @Override
    public Result filter(Logger logger, Level level, @Nullable Marker marker, @Nullable String message,
            Object... params) {
        if (message != null && level.isMoreSpecificThan(Level.WARN)) {
            ParameterizedMessage said = new ParameterizedMessage(message, params);
            note(logger, said.getFormattedMessage(), said.getThrowable());
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, @Nullable Marker marker, @Nullable Object message,
            @Nullable Throwable thrown) {
        if (message != null && level.isMoreSpecificThan(Level.WARN)) {
            note(logger, String.valueOf(message), thrown);
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, @Nullable Marker marker, @Nullable Message message,
            @Nullable Throwable thrown) {
        if (message != null && level.isMoreSpecificThan(Level.WARN)) {
            note(logger, message.getFormattedMessage(), thrown == null ? message.getThrowable() : thrown);
        }
        return Result.NEUTRAL;
    }

    private static void note(Logger logger, String said, @Nullable Throwable thrown) {
        String name = logger.getName();
        if (name.startsWith(HARNESS)) {
            return;
        }
        if (name.startsWith(OWN)) {
            SwitchFaults.note(said.startsWith(PREFIX) ? said.substring(PREFIX.length()) : said, thrown);
        } else if (thrown != null && findOwnFrame(thrown) != null) {
            SwitchFaults.note(said + " (" + thrown + ")", thrown);
            ownThrow.accept(said, thrown);
        }
    }

    public static @Nullable String findOwnFrame(Throwable thrown) {
        int depth = 0;
        for (Throwable at = thrown; at != null && depth < 16; at = at.getCause(), depth++) {
            for (StackTraceElement frame : at.getStackTrace()) {
                String type = frame.getClassName();
                if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.")) {
                    continue;
                }
                if (type.startsWith(OWN) && !type.startsWith(HARNESS)) {
                    return type + "." + frame.getMethodName();
                }
                break;
            }
        }
        return null;
    }
}
