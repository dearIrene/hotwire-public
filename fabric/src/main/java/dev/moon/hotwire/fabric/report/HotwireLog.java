package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.report.GameLog;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.util.Throwables;

public final class HotwireLog extends AbstractAppender {
    public static final String FILE = "latest.log";
    public static final String LAST = "previous.log";
    private static final String PACKAGE = "dev.moon.hotwire";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Writer out;

    private final boolean handsOn;

    private HotwireLog(Writer out, boolean handsOn) {
        super("Hotwire", null, null, true, Property.EMPTY_ARRAY);
        this.out = out;
        this.handsOn = handsOn;
    }

    private static boolean installed;

    public static synchronized void install(Path folder) {
        if (installed || !(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        installed = true;
        Writer out;
        try {
            Files.createDirectories(folder);
            Path file = folder.resolve(FILE);
            if (Files.exists(file)) {
                Files.move(file, folder.resolve(LAST), StandardCopyOption.REPLACE_EXISTING);
            }
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            GameLog.LOGGER.warn("[Hotwire] its own log could not be opened, so the game's log has all of it", e);
            return;
        }
        boolean test = isTestLog();
        HotwireLog appender = new HotwireLog(out, !test);
        appender.start();

        Level level = Boolean.getBoolean("hotwire" + ".debug") ? Level.DEBUG : Level.INFO;
        LoggerConfig ours = new LoggerConfig(PACKAGE, level, test);
        ours.addAppender(appender, null, null);
        context.getConfiguration().addLogger(PACKAGE, ours);
        context.updateLoggers();
    }

    public static boolean isTestLog() {
        return System.getProperty("hotwire.oneclient") != null
                && !Boolean.parseBoolean(System.getProperty("hotwire.playerlog"));
    }

    @Override
    public void append(LogEvent event) {
        StringBuilder line = new StringBuilder().append('[')
                .append(TIME.format(Instant.ofEpochMilli(event.getInstant().getEpochMillisecond()))).append("] [")
                .append(event.getThreadName()).append('/').append(event.getLevel()).append("]: ")
                .append(event.getMessage().getFormattedMessage()).append('\n');
        if (event.getThrown() != null) {
            Throwables.toStringList(event.getThrown()).forEach(part -> line.append(part).append('\n'));
        }
        synchronized (this.out) {
            try {
                this.out.append(line);
                this.out.flush();
            } catch (IOException e) {
            }
        }
        if (this.handsOn && (event.getLevel().isMoreSpecificThan(Level.WARN)
                || GameLog.class.getName().equals(event.getLoggerName()))
                && LogManager.getContext(false) instanceof LoggerContext context) {
            context.getConfiguration().getRootLogger().log(event);
        }
    }
}
