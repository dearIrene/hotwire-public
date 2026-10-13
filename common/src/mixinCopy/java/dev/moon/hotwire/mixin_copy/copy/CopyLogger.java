package dev.moon.hotwire.mixin_copy.copy;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

final class CopyLogger extends LoggerAdapterAbstract {
    private static final Set<Level> LOUD = EnumSet.of(Level.FATAL, Level.ERROR, Level.WARN);

    CopyLogger(String name) {
        super(name);
    }

    @Override
    public String getType() {
        return "Hotwire copy";
    }

    @Override
    public void catching(Level level, Throwable failure) {
        this.log(level, "caught", failure);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        if (!LOUD.contains(level) || message.startsWith("Reference map ")
                || message.startsWith("MixinEnvironment::setCompatibilityLevel is deprecated")) {
            return;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (Object param : params) {
            int at = message.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            out.append(message, from, at).append(param);
            from = at + 2;
        }
        out.append(message.substring(from));
        send(level, out.toString());
    }

    @Override
    public void log(Level level, String message, Throwable failure) {
        if (LOUD.contains(level)) {
            send(level, message + ": " + failure);
        }
    }

    @Override
    public <T extends Throwable> T throwing(T failure) {
        return failure;
    }

    private static void send(Level level, String message) {
        BiConsumer<String, String> sink = CopyMixinService.getLog();
        if (sink != null) {
            sink.accept(level.name(), message);
        }
    }
}
