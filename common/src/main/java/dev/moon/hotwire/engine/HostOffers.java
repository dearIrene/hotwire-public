package dev.moon.hotwire.engine;

import dev.moon.hotwire.api.ChangePlan;
import dev.moon.hotwire.api.ChangeRequest;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HostOffers {
    private final HotwireEngine engine;
    private final InstalledMods installed;
    private final Map<String, SharedChange> waiting = new LinkedHashMap<>();

    HostOffers(HotwireEngine engine, InstalledMods installed) {
        this.engine = engine;
        this.installed = installed;
    }

    void take(List<SharedChange> told) {
        boolean more = false;
        for (SharedChange change : told) {
            if (!this.differs(change)) {
                this.waiting.remove(change.mod());
            } else if (this.waiting.containsKey(change.mod()) || this.waiting.size() < SharedChange.MOST) {
                more |= !change.equals(this.waiting.put(change.mod(), change));
            }
        }
        if (!more) {
            return;
        }
        ModScreen shown = this.engine.screen;
        if (shown != null && Games.getNow().isHotwireShown()) {
            shown.offerHostChanges();
        } else {
            Notices.show(Notice.ofAsk(Lang.get("hotwire.host.title"), Lang.get("hotwire.host.notice")));
        }
    }

    List<SharedChange> getWaiting() {
        this.waiting.values().removeIf(change -> !this.differs(change));
        return List.copyOf(this.waiting.values());
    }

    void clear() {
        this.waiting.clear();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void match() {
        ChangeRequest request = new ChangeRequest();
        for (SharedChange change : this.getWaiting()) {
            request = switch (change.kind()) {
                case ADD -> request.add(change.project(), change.version());
                case ON -> request.on(change.mod());
                case OFF -> request.off(change.mod());
            };
        }
        this.clear();
        EngineApi.onGame(ApiChanges.plan(request, new CallerBlocks.Callers(List.of(), true)))
                .thenCompose(ChangePlan::accept).thenAccept(result -> this.engine.showMessage(result.message(),
                        result.kind() != SwitchResult.Kind.DONE));
    }

    private boolean differs(SharedChange change) {
        boolean here = this.installed.find(change.mod()).isPresent();
        return switch (change.kind()) {
            case ADD -> !here;
            case ON -> here && !this.engine.isOn(change.mod());
            case OFF -> here && this.engine.isOn(change.mod());
        };
    }
}
