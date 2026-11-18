package dev.moon.hotwire.registration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RecorderSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecorderSupport.class);
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private RecorderSupport() {
    }

    public static CallerOwner.@Nullable Owner findOwner() {
        List<CallerOwner.Frame> stack = STACK.walk(frames -> frames.<CallerOwner.Frame>map(frame ->
                new CallerOwner.Frame(frame.getDeclaringClass(), frame.getMethodName())).toList());
        return Owners.getCallers().findFrames(stack);
    }

    public static boolean isFirst(Object key) {
        RegistrationJournal journal = GameRegistrations.getJournal();
        if (journal.isRestoring()) {
            return false;
        }
        Set<Object> known = journal.getRecorderKeys();
        synchronized (known) {
            if (known.add(key)) {
                return true;
            }
        }
        Map<Object, Long> sequences = journal.getRecorderSequences();
        Long sequence;
        synchronized (sequences) {
            sequence = sequences.get(key);
        }
        return sequence != null && !journal.isLive(sequence);
    }

    public static void recordAs(String mod, Object key, Registration registration) {
        noteSequence(key, GameRegistrations.getJournal().record(mod, registration));
    }

    public static boolean record(Object key, Registration registration) {
        CallerOwner.Owner owner = findOwner();
        if (owner == null) {
            String mod = Owners.findModOf(key.getClass());
            if (mod != null && Owners.isRecordedMod(mod)) {
                recordAs(mod, key, registration);
                return true;
            }
            logUnowned(registration);
            return false;
        }
        noteSequence(key, GameRegistrations.getJournal().record(owner.mod(), registration));
        return true;
    }

    private static void noteSequence(Object key, long sequence) {
        Map<Object, Long> sequences = GameRegistrations.getJournal().getRecorderSequences();
        synchronized (sequences) {
            sequences.put(key, sequence);
        }
    }

    private static void logUnowned(Registration registration) {
        String what = registration.getKind() + " " + registration.describe();
        Set<String> unowned = GameRegistrations.getJournal().getUnownedLines();
        synchronized (unowned) {
            if (!unowned.add(what)) {
                return;
            }
        }
        List<String> frames = STACK.walk(stack -> stack.skip(3).limit(8)
                .map(frame -> frame.getDeclaringClass().getName() + "#" + frame.getMethodName()).toList());
        LOGGER.info("[Hotwire] {} has no owning mod on the stack and is not recorded; made from {}", what,
                String.join(" < ", frames));
    }

    public static long getSequenceOf(Object key) {
        Map<Object, Long> sequences = GameRegistrations.getJournal().getRecorderSequences();
        synchronized (sequences) {
            return sequences.getOrDefault(key, Long.MAX_VALUE);
        }
    }
}
