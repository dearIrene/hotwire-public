package dev.moon.hotwire.engine;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.report.LoggedFaults;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.notices.Notices;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class OwnThrowNotice {
    private static final int MOST = 20;

    private OwnThrowNotice() {
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void install(Tasks tasks, BiConsumer<String, Boolean> say, Function<String, String> writeReport) {
        Set<String> told = ConcurrentHashMap.newKeySet();
        LoggedFaults.onOwnThrow((said, thrown) -> {
            String kind = thrown.getClass().getName() + " at " + LoggedFaults.findOwnFrame(thrown);
            if (told.size() >= MOST || !told.add(kind)) {
                return;
            }
            String what = said + " (" + thrown + ")";
            tasks.submit("report of what Hotwire threw inside the game", () -> writeReport.apply(
                    "Hotwire's own code threw inside the game, which went on past it: " + what), where -> {
                        String line = Lang.get("hotwire.own_fault", what, where);
                        say.accept(line, true);
                        Notices.show(Lang.get("hotwire.own_fault.title"), line);
                    });
        });
    }
}
