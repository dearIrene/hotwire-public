package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogAppenders {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogAppenders.class);

    private record Removal(String logger, Appender appender) {
    }

    private static final Map<String, List<Removal>> REMOVED = new ConcurrentHashMap<>();

    private LogAppenders() {
    }

    public static void takeOut(Collection<String> mods) {
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        Configuration config = context.getConfiguration();
        List<LoggerConfig> loggers = new ArrayList<>(config.getLoggers().values());
        loggers.add(config.getRootLogger());
        for (LoggerConfig logger : loggers) {
            for (Appender appender : List.copyOf(logger.getAppenders().values())) {
                String mod = GameRegistrations.findModOf(appender.getClass());
                if (mod != null && mods.contains(mod)) {
                    logger.removeAppender(appender.getName());
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(logger.getName(), appender));
                    LOGGER.info("[Hotwire] {}'s log appender {} is taken off {}", mod, appender.getName(),
                            logger.getName().isEmpty() ? "the root logger" : logger.getName());
                }
            }
        }
        context.updateLoggers();
    }

    public static void putBack(Collection<String> mods) {
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        Configuration config = context.getConfiguration();
        for (String mod : mods) {
            List<Removal> removed = REMOVED.remove(mod);
            if (removed == null) {
                continue;
            }
            for (Removal one : removed) {
                LoggerConfig logger = one.logger().isEmpty() ? config.getRootLogger()
                        : config.getLoggerConfig(one.logger());
                logger.addAppender(one.appender(), null, null);
            }
        }
        context.updateLoggers();
    }
}
