package dev.moon.hotwire.fabric.registration;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

public final class QuietThreads extends AbstractFilter {
    private QuietThreads() {
        super(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL);
    }

    public static void install() {
        if (System.getProperty("hotwire.oneclient") == null
                && LogManager.getContext(false) instanceof LoggerContext context) {
            context.getConfiguration().getRootLogger().addFilter(new QuietThreads());
        }
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        String thread = event.getThreadName();
        boolean ahead = thread != null && (thread.startsWith("hotwire-preload") || thread.startsWith("hotwire-ahead"));
        return ahead && event.getLevel().isLessSpecificThan(Level.INFO) ? Filter.Result.DENY : Filter.Result.NEUTRAL;
    }
}
