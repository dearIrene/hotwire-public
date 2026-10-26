package dev.moon.hotwire.switching;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class CrashPolicy {
    public enum Site { FRAME, PACKET, ENTITY, BLOCK_ENTITY, PLAYER, SERVER_TICK, SERVER_STATUS }

    public record Caught(String mod, Site site, Throwable thrown) {
    }

    private enum State { TURNING_OFF, OFF_REFUSED }

    private final Function<Throwable, @Nullable String> ownerOf;
    private final Predicate<String> guarded;
    private final Consumer<Caught> turnOff;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CrashPolicy(Function<Throwable, @Nullable String> ownerOf, Predicate<String> guarded,
            Consumer<Caught> turnOff) {
        this.ownerOf = ownerOf;
        this.guarded = guarded;
        this.turnOff = turnOff;
    }

    public boolean handle(Throwable thrown, Site site) {
        if (isJvmFailure(thrown)) {
            return false;
        }
        String mod = this.ownerOf.apply(thrown);
        if (mod == null || !this.guarded.test(mod)) {
            return false;
        }
        State before = this.states.putIfAbsent(mod, State.TURNING_OFF);
        if (before == null) {
            this.turnOff.accept(new Caught(mod, site, thrown));
            return true;
        }
        return before == State.TURNING_OFF;
    }

    public void onOffFinished(String mod, boolean off) {
        if (off) {
            this.states.remove(mod);
        } else {
            this.states.put(mod, State.OFF_REFUSED);
        }
    }

    private static boolean isJvmFailure(Throwable thrown) {
        int depth = 0;
        for (Throwable at = thrown; at != null && depth < 32; at = at.getCause(), depth++) {
            if (at instanceof VirtualMachineError) {
                return true;
            }
        }
        return false;
    }
}
