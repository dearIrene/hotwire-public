package dev.moon.hotwire.report;

import java.util.List;
import org.jspecify.annotations.Nullable;

public final class CrashBlame {
    static final long RIGHT_AFTER_SECONDS = 60;
    private static final String SEND = " This one is Hotwire's: send the newest report under hotwire/reports.";
    private static final String TELL = " To tell whose bug: play the same way without switching; still crashes, the"
            + " mod's; does not, Hotwire's, send the newest report under hotwire/reports.";

    private CrashBlame() {
    }

    public enum Answer { OURS, TIED, UNTIED, NOT_OURS }

    public record Session(@Nullable String underWay, @Nullable String proof, List<String> ties, String lastChange,
            long sinceChange, boolean caughtEarlier, @Nullable String caughtBefore) {
        public static final Session NONE = new Session(null, null, List.of(), "", -1, false, null);

        public Session {
            ties = List.copyOf(ties);
        }
    }

    public static Answer answerOf(String decision) {
        if (CrashSection.isProven(decision)) {
            return Answer.OURS;
        }
        if (CrashSection.isHotwires(decision)) {
            return Answer.TIED;
        }
        return CrashSection.isCleared(decision) ? Answer.NOT_OURS : Answer.UNTIED;
    }

    public static CrashSection.Detail decide(CrashSection.Detail trace, Session session) {
        String said = trace.value();
        if (CrashSection.isProven(said)) {
            return trace;
        }
        if (session.proof() != null) {
            return new CrashSection.Detail(trace.name(), "Hotwire involved? YES. " + session.proof() + "." + SEND);
        }
        if (session.underWay() != null) {
            return new CrashSection.Detail(trace.name(), "Hotwire involved? YES. The game crashed while Hotwire was"
                    + " switching (" + session.underWay() + ")." + SEND);
        }

        if (session.caughtBefore() != null) {
            String first = session.caughtBefore();
            return new CrashSection.Detail(trace.name(), switch (answerOf(first)) {
                case OURS -> "Hotwire involved? YES. It follows a crash Hotwire kept the game through a moment"
                        + " before, and that one was Hotwire's." + SEND;
                case TIED -> "Hotwire involved? YES. It follows a crash Hotwire kept the game through a moment"
                        + " before, which a change of Hotwire's was on the path of." + TELL;
                case UNTIED -> "Hotwire involved? MAYBE. It follows a crash Hotwire kept the game through a moment"
                        + " before; of that one: " + first.substring(first.indexOf('.') + 1).strip();
                case NOT_OURS -> "Hotwire involved? NO. It follows a crash Hotwire kept the game through a moment"
                        + " before, in a session Hotwire had changed nothing in: the same crash, going on.";
            });
        }
        if (CrashSection.isHotwires(said)) {
            return trace;
        }
        boolean changed = !session.lastChange().isEmpty() || session.caughtEarlier();
        if (!session.ties().isEmpty()) {
            return new CrashSection.Detail(trace.name(), "Hotwire involved? YES. " + String.join("; ", session.ties())
                    + "." + TELL);
        }
        if (!changed || (!CrashSection.isCleared(said) && !said.contains("only further down, as callers"))) {
            return trace;
        }
        String last = session.lastChange().isEmpty() ? "it kept the game through a crash"
                : "'" + session.lastChange() + "' ended " + session.sinceChange() + " s before";
        if (!session.lastChange().isEmpty() && session.sinceChange() <= RIGHT_AFTER_SECONDS) {
            return new CrashSection.Detail(trace.name(), "Hotwire involved? YES. The game crashed right after a"
                    + " change of Hotwire's: " + last + "." + TELL);
        }
        return new CrashSection.Detail(trace.name(), "Hotwire involved? MAYBE. Hotwire changed the game this session ("
                + last + "), and nothing it changed is in this crash. To tell: play the same way without switching;"
                + " still crashes, not Hotwire's; does not, send the newest report under hotwire/reports.");
    }
}
