package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FinalWrites;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogAppenders {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogAppenders.class);

    private enum Update implements Runnable {
        ONCE;

        @Override
        public void run() {
            if (LogManager.getContext(false) instanceof LoggerContext context) {
                context.updateLoggers();
            }
        }
    }

    private static LoggerConfig findLogger(String name) {
        Configuration config = ((LoggerContext) LogManager.getContext(false)).getConfiguration();
        return name.isEmpty() ? config.getRootLogger() : config.getLoggerConfig(name);
    }

    private record Removal(String logger, Appender appender) implements TakenOut.Entry {
        @Override
        public void restore() {
            findLogger(this.logger).addAppender(this.appender, null, null);
        }

        @Override
        public String describe() {
            return "the log appender " + this.appender.getName();
        }

        @Override
        public Runnable afterBatch() {
            return Update.ONCE;
        }
    }

    private record FilterRemoval(String logger, Filter filter) implements TakenOut.Entry {
        @Override
        public void restore() {
            findLogger(this.logger).addFilter(this.filter);
        }

        @Override
        public String describe() {
            return "the log filter " + this.filter.getClass().getName();
        }

        @Override
        public Runnable afterBatch() {
            return Update.ONCE;
        }
    }

    private record PolicyRemoval(RewriteAppender appender, Object policy) implements TakenOut.Entry {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public void restore() {
            try {
                Field slot = findPolicyField();
                if (slot.get(this.appender) == UNCHANGED) {
                    FinalWrites.write(slot, this.appender, this.policy);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("the log's rewrite policy", e);
            }
        }

        @Override
        public String describe() {
            return "the log's rewrite policy " + this.policy.getClass().getName();
        }

        @Override
        public Runnable afterBatch() {
            return Update.ONCE;
        }
    }

    private static final RewritePolicy UNCHANGED = event -> event;

    private LogAppenders() {
    }

    public static void takeOut(Collection<String> mods) {
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        Configuration config = context.getConfiguration();

        Set<LoggerConfig> loggers = Collections.newSetFromMap(new IdentityHashMap<>());
        loggers.addAll(config.getLoggers().values());
        loggers.add(config.getRootLogger());
        for (LoggerConfig logger : loggers) {
            for (Appender appender : List.copyOf(logger.getAppenders().values())) {
                if (appender instanceof RewriteAppender rewrite) {
                    takeOutPolicy(rewrite, mods);
                }
                String mod = GameRegistrations.findCodeMod(appender.getClass());
                if (mod != null && mods.contains(mod)) {
                    logger.removeAppender(appender.getName());
                    TakenOut.add(mod, new Removal(logger.getName(), appender));
                    LOGGER.info("[Hotwire] {}'s log appender {} is taken off {}", mod, appender.getName(),
                            logger.getName().isEmpty() ? "the root logger" : logger.getName());
                }
            }
            takeOutFilters(logger, mods);
        }
        context.updateLoggers();
    }

    private static void takeOutFilters(LoggerConfig logger, Collection<String> mods) {
        Filter filter = logger.getFilter();
        List<Filter> filters = filter instanceof CompositeFilter composite ? List.of(composite.getFiltersArray())
                : filter == null ? List.of() : List.of(filter);
        for (Filter one : filters) {
            String mod = GameRegistrations.findCodeMod(one.getClass());
            if (mod != null && mods.contains(mod)) {
                logger.removeFilter(one);
                TakenOut.add(mod, new FilterRemoval(logger.getName(), one));
                LOGGER.info("[Hotwire] {}'s log filter is taken off {}", mod,
                        logger.getName().isEmpty() ? "the root logger" : logger.getName());
            }
        }
    }

    private static Field findPolicyField() throws NoSuchFieldException {
        Field field = RewriteAppender.class.getDeclaredField("rewritePolicy");
        field.setAccessible(true);
        return field;
    }

    private static void takeOutPolicy(RewriteAppender appender, Collection<String> mods) {
        try {
            Field slot = findPolicyField();
            Object policy = slot.get(appender);
            if (policy == null) {
                return;
            }
            String mod = GameRegistrations.findCodeMod(policy.getClass());
            if (mod != null && mods.contains(mod)) {
                FinalWrites.write(slot, appender, UNCHANGED);
                TakenOut.add(mod, new PolicyRemoval(appender, policy));
                LOGGER.info("[Hotwire] {}'s rewrite of every log line gives way to lines as they come", mod);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] the log's rewrite appender {} keeps its policy: {}", appender.getName(),
                    e.toString());
        }
    }

}
