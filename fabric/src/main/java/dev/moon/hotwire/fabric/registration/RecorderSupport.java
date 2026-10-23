package dev.moon.hotwire.fabric.registration;

import com.google.common.collect.MapMaker;
import dev.moon.hotwire.registration.CallerOwner;
import dev.moon.hotwire.registration.Registration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RecorderSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecorderSupport.class);
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final Set<Object> KNOWN = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());
    private static final Map<Object, Long> SEQUENCES = new MapMaker().weakKeys().makeMap();
    private static final Set<String> UNOWNED = new HashSet<>();

    private RecorderSupport() {
    }

    static CallerOwner.@Nullable Owner findOwner() {
        List<CallerOwner.Frame> stack = STACK.walk(frames -> frames.<CallerOwner.Frame>map(frame ->
                new CallerOwner.Frame(frame.getDeclaringClass(), frame.getMethodName())).toList());
        return GameRegistrations.getOwners().findFrames(stack);
    }

    static boolean isFirst(Object key) {
        if (GameRegistrations.getJournal().isRestoring()) {
            return false;
        }
        synchronized (KNOWN) {
            if (KNOWN.add(key)) {
                return true;
            }
        }
        Long sequence;
        synchronized (SEQUENCES) {
            sequence = SEQUENCES.get(key);
        }
        return sequence != null && !GameRegistrations.getJournal().isLive(sequence);
    }

    static void recordAs(String mod, Object key, Registration registration) {
        long sequence = GameRegistrations.getJournal().record(mod, null, registration);
        synchronized (SEQUENCES) {
            SEQUENCES.put(key, sequence);
        }
    }

    static boolean record(Object key, Registration registration) {
        CallerOwner.Owner owner = findOwner();
        if (owner == null) {
            logUnowned(registration);
            return false;
        }
        long sequence = GameRegistrations.getJournal().record(owner.mod(), owner.via(), registration);
        synchronized (SEQUENCES) {
            SEQUENCES.put(key, sequence);
        }
        return true;
    }

    private static void logUnowned(Registration registration) {
        String what = registration.getKind() + " " + registration.describe();
        synchronized (UNOWNED) {
            if (!UNOWNED.add(what)) {
                return;
            }
        }
        List<String> frames = STACK.walk(stack -> stack.skip(3).limit(8)
                .map(frame -> frame.getDeclaringClass().getName() + "#" + frame.getMethodName()).toList());
        LOGGER.debug("[Hotwire] {} has no owning mod on the stack and is not recorded; made from {}", what,
                String.join(" < ", frames));
    }

    static long getSequenceOf(Object key) {
        synchronized (SEQUENCES) {
            return SEQUENCES.getOrDefault(key, Long.MAX_VALUE);
        }
    }
}
