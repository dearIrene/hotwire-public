package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.FinalWrites;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private record Removal(String logger, Appender appender) {
    }

    private record FilterRemoval(String logger, Filter filter) {
    }

    private record PolicyRemoval(RewriteAppender appender, Object policy) {
    }

    private static final RewritePolicy UNCHANGED = event -> event;
    private static final Map<String, List<Removal>> REMOVED = new ConcurrentHashMap<>();
    private static final Map<String, List<PolicyRemoval>> POLICIES = new ConcurrentHashMap<>();
    private static final Map<String, List<FilterRemoval>> FILTERS_REMOVED = new ConcurrentHashMap<>();

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
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(logger.getName(), appender));
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
                FILTERS_REMOVED.computeIfAbsent(mod, any -> new ArrayList<>())
                        .add(new FilterRemoval(logger.getName(), one));
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
                POLICIES.computeIfAbsent(mod, any -> new ArrayList<>()).add(new PolicyRemoval(appender, policy));
                LOGGER.info("[Hotwire] {}'s rewrite of every log line gives way to lines as they come", mod);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[Hotwire] the log's rewrite appender {} keeps its policy: {}", appender.getName(),
                    e.toString());
        }
    }

    @SuppressWarnings("ReferenceEquality")
    public static void putBack(Collection<String> mods) {
        for (String mod : mods) {
            for (PolicyRemoval one : POLICIES.getOrDefault(mod, List.of())) {
                try {
                    Field slot = findPolicyField();
                    if (slot.get(one.appender()) == UNCHANGED) {
                        FinalWrites.write(slot, one.appender(), one.policy());
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.info("[Hotwire] {}'s rewrite of log lines could not go back: {}", mod, e.toString());
                }
            }
            POLICIES.remove(mod);
        }
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
        for (String mod : mods) {
            for (FilterRemoval one : FILTERS_REMOVED.getOrDefault(mod, List.of())) {
                (one.logger().isEmpty() ? config.getRootLogger() : config.getLoggerConfig(one.logger()))
                        .addFilter(one.filter());
            }
            FILTERS_REMOVED.remove(mod);
        }
        context.updateLoggers();
    }
}
