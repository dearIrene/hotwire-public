package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.report.HotwireLog;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;

public final class QuietThreads extends AbstractFilter {
    private QuietThreads() {
        super(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL);
    }

    public static void install() {
        if (HotwireLog.isTestLog() || !(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        LoggerConfig root = context.getConfiguration().getRootLogger();
        Filter before = root.getFilter();
        List<Filter> others = before instanceof CompositeFilter composite ? List.of(composite.getFiltersArray())
                : before == null ? List.of() : List.of(before);
        if (before != null) {
            root.removeFilter(before);
        }
        root.addFilter(new QuietThreads());
        others.forEach(root::addFilter);
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        String logger = event.getLoggerName();
        String thread = event.getThreadName();
        if ((logger != null && logger.startsWith("dev.moon.hotwire")) || thread == null) {
            return Filter.Result.NEUTRAL;
        }
        if ((thread.startsWith("hotwire-preload") || thread.startsWith("hotwire-ahead"))
                && event.getLevel().isLessSpecificThan(Level.INFO)) {
            return Filter.Result.DENY;
        }

        return event.getLevel().isLessSpecificThan(Level.WARN)
                && MixinCopy.isMixingAt(thread, event.getInstant().getEpochMillisecond())
                ? Filter.Result.DENY : Filter.Result.NEUTRAL;
    }
}
