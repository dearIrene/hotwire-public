package dev.moon.hotwire.report;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record NativeCrash(String error, String thread, List<Frame> frames, boolean redefining,
        OptionalDouble sinceChange, boolean agentLoaded) {
    private static final Pattern ERROR = Pattern.compile("^#\\s+(EXCEPTION_\\w+|SIG\\w+|Internal Error[^,]*"
            + "|Out of Memory Error[^,]*)");
    private static final Pattern THREAD = Pattern.compile("^Current thread \\([^)]*\\):\\s+(\\w+)(?: \"([^\"]*)\")?");
    private static final Pattern ELAPSED = Pattern.compile("elapsed time: ([\\d.]+) seconds");
    private static final Pattern EVENT = Pattern.compile("^Event: ([\\d.]+) (.*)$");
    private static final Pattern LIBRARY = Pattern.compile("^[CV]\\s+\\[([^+\\]]+)");
    private static final Pattern JAVA = Pattern.compile("^[jJ]\\s+(?:\\d+\\s+)?(?:c\\d\\s+)?([^\\s(]+)\\(");

    private static final Pattern RUN_PART = Pattern.compile("\\+0x[0-9a-f]+|\\$\\$Lambda[^.]*|\\$\\d+(?=[.$]|$)");
    private static final String REDEFINE = "RedefineClasses";
    private static final String AGENT_LIBRARY = "instrument.";
    private static final int SIGNED_FRAMES = 8;

    public record Frame(@Nullable String library, @Nullable String method) {
        public @Nullable String type() {
            return this.method == null ? null : this.method.substring(0, Math.max(0, this.method.lastIndexOf('.')));
        }
    }

    public NativeCrash {
        frames = List.copyOf(frames);
    }

    public static boolean isOne(String text) {
        return text.contains("A fatal error has been detected by the Java Runtime Environment");
    }

    public static NativeCrash read(String text) {
        String error = "";
        String thread = "";
        List<Frame> frames = new ArrayList<>();
        double elapsed = -1;
        double changed = -1;
        boolean redefining = false;
        boolean operation = false;
        boolean agent = false;
        boolean inFrames = false;
        boolean inLibraries = false;
        boolean problematic = false;
        for (Iterator<String> lines = text.lines().iterator(); lines.hasNext();) {
            String line = lines.next();
            if (line.startsWith("#")) {
                Matcher found = ERROR.matcher(line);
                if (error.isEmpty() && found.find()) {
                    error = found.group(1).strip();
                } else if (problematic) {
                    Frame frame = readFrame(line.substring(1).strip());
                    if (frame != null) {
                        frames.add(frame);
                    }
                }
                problematic = line.contains("Problematic frame:");
                continue;
            }
            if (line.startsWith("Native frames:") || line.startsWith("Java frames:")) {
                inFrames = true;
                continue;
            }
            if (inFrames) {
                if (line.isBlank()) {
                    inFrames = false;
                } else {
                    Frame frame = readFrame(line);
                    if (frame != null && (frames.isEmpty() || !frame.equals(frames.getLast()))) {
                        frames.add(frame);
                    }
                }
                continue;
            }
            if (line.startsWith("Dynamic libraries:")) {
                inLibraries = true;
            } else if (inLibraries) {
                inLibraries = !line.isBlank();
                agent |= line.contains(AGENT_LIBRARY);
            } else if (line.startsWith("VM_Operation") && line.contains(REDEFINE)) {
                operation = true;
            } else if (line.startsWith("Current thread")) {
                Matcher found = THREAD.matcher(line);
                if (found.find()) {
                    thread = found.group(2) == null ? found.group(1) : found.group(2);
                }
            } else if (line.startsWith("Time:")) {
                Matcher found = ELAPSED.matcher(line);
                if (found.find()) {
                    elapsed = Double.parseDouble(found.group(1));
                }
            } else if (line.startsWith("Event:")) {
                Matcher found = EVENT.matcher(line);
                String what = found.find() ? found.group(2) : "";
                if (what.contains(REDEFINE) || what.contains(" redefined class ")) {
                    changed = Math.max(changed, Double.parseDouble(found.group(1)));
                    if (what.contains(REDEFINE)) {
                        redefining = !what.endsWith(" done");
                    }
                }
            }
        }
        return new NativeCrash(error, thread, frames, redefining || operation, changed < 0 || elapsed < changed
                ? OptionalDouble.empty() : OptionalDouble.of(elapsed - changed), agent);
    }

    public String sign() {
        StringBuilder out = new StringBuilder(this.error);
        int signed = 0;
        String last = "";
        for (Frame frame : this.frames) {
            if (frame.library() != null) {
                if (!frame.library().equals(last)) {
                    out.append('|').append(frame.library());
                }
                last = frame.library();
            } else if (frame.method() != null && signed++ < SIGNED_FRAMES) {
                out.append('|').append(RUN_PART.matcher(frame.method()).replaceAll(""));
            }
        }
        return out.toString();
    }

    private static @Nullable Frame readFrame(String line) {
        Matcher library = LIBRARY.matcher(line);
        if (library.find()) {
            return new Frame(library.group(1), null);
        }
        Matcher java = JAVA.matcher(line);
        return java.find() ? new Frame(null, java.group(1)) : null;
    }
}
