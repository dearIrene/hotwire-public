package dev.moon.hotwire.fabric;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.fabric.report.LoggedFaults;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class OwnThrowNotice {
    private static final int MOST = 20;
    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();

    private OwnThrowNotice() {
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static void install(Tasks tasks, BiConsumer<String, Boolean> say, Function<String, String> writeReport) {
        LoggedFaults.onOwnThrow((said, thrown) -> {
            String kind = thrown.getClass().getName() + " at " + LoggedFaults.findOwnFrame(thrown);
            if (TOLD.size() >= MOST || !TOLD.add(kind)) {
                return;
            }
            String what = said + " (" + thrown + ")";
            tasks.submit("report of what Hotwire threw inside the game", () -> writeReport.apply(
                    "Hotwire's own code threw inside the game, which went on past it: " + what), where -> {
                        String line = "Hotwire's own code failed inside the game, which went on past it: " + what
                                + ". Restarting the game is the safe way on. " + where;
                        say.accept(line, true);
                        ModViews.showToast("Hotwire caught a fault of its own", line);
                    });
        });
    }
}
