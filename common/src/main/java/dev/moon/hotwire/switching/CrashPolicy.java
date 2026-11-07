package dev.moon.hotwire.switching;

import dev.moon.hotwire.report.Causes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CrashPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashPolicy.class);
    public enum Site { FRAME, PACKET, ENTITY, BLOCK_ENTITY, PLAYER, SERVER_TICK, SERVER_STATUS }

    public record Caught(String mod, Site site, Throwable thrown) {
    }

    private enum State { TURNING_OFF, OFF_REFUSED }

    private record DevThrow(String type, StackTraceElement top, String mod) {
    }

    private final Function<Throwable, @Nullable String> ownerOf;
    private final Predicate<String> guarded;
    private final Consumer<Caught> turnOff;
    private final Predicate<String> inDevelopment;
    private final Consumer<Caught> onDevThrow;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile @Nullable DevThrow lastDevThrow;
    private volatile boolean toldFrameless;
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    public CrashPolicy(Function<Throwable, @Nullable String> ownerOf, Predicate<String> guarded,
            Consumer<Caught> turnOff, Predicate<String> inDevelopment, Consumer<Caught> onDevThrow) {
        this.ownerOf = ownerOf;
        this.guarded = guarded;
        this.turnOff = turnOff;
        this.inDevelopment = inDevelopment;
        this.onDevThrow = onDevThrow;
    }

    public boolean handle(Throwable thrown, Site site) {
        if (isJvmFailure(thrown)) {
            return false;
        }
        String mod = this.findOwner(thrown);
        if (mod != null && this.inDevelopment.test(mod)) {
            this.onDevThrow.accept(new Caught(mod, site, thrown));
            return true;
        }
        if (mod == null || !this.guarded.test(mod)) {
            return false;
        }
        State before = this.states.putIfAbsent(mod, State.TURNING_OFF);
        if (before == null) {
            this.turnOff.accept(new Caught(mod, site, thrown));
            return true;
        }
        if (before != State.TURNING_OFF) {
            return false;
        }

        StackTraceElement[] frames = thrown.getStackTrace();
        String where = mod + " at " + site + ": " + thrown + (frames.length == 0 ? "" : " < " + frames[0]);
        if (this.said.add(where)) {
            LOGGER.warn("[Hotwire] {}'s throw was dropped, as its off is under way or done: {}", mod, where);
        }
        return true;
    }

    private @Nullable String findOwner(Throwable thrown) {
        Throwable inner = Causes.findInnermost(thrown);
        StackTraceElement[] frames = inner.getStackTrace();
        DevThrow last = this.lastDevThrow;
        if (last != null && last.type().equals(inner.getClass().getName())
                && (frames.length == 0 || last.top().equals(frames[0])) && this.inDevelopment.test(last.mod())) {
            if (frames.length == 0 && !this.toldFrameless) {
                this.toldFrameless = true;
                LOGGER.info("[Hotwire] a {} with no frames, as the JIT throws a repeated one, taken as {}'s at {}",
                        last.type(), last.mod(), last.top());
            }
            return last.mod();
        }
        String mod = this.ownerOf.apply(thrown);
        if (mod != null && frames.length > 0 && this.inDevelopment.test(mod)) {
            this.lastDevThrow = new DevThrow(inner.getClass().getName(), frames[0], mod);
        }
        return mod;
    }

    public void onOffFinished(String mod, boolean off) {
        if (off) {
            this.states.remove(mod);
        } else {
            this.states.put(mod, State.OFF_REFUSED);
        }
    }

    private static boolean isJvmFailure(Throwable thrown) {
        return Causes.hasCause(thrown, VirtualMachineError.class);
    }
}
