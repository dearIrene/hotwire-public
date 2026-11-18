package dev.moon.hotwire.engine;

import dev.moon.hotwire.api.ChangeRequest;
import dev.moon.hotwire.api.Reason;
import dev.moon.hotwire.api.SwitchResult;
import dev.moon.hotwire.seam.Games;
import dev.moon.hotwire.seam.SharedChange;
import dev.moon.hotwire.switching.CallerBlocks;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.notices.Notice;
import dev.moon.hotwire.ui.notices.Notices;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class HostOffers {
    private final HotwireEngine engine;
    private final InstalledMods installed;
    private final Map<String, SharedChange> waiting = new LinkedHashMap<>();

    HostOffers(HotwireEngine engine, InstalledMods installed) {
        this.engine = engine;
        this.installed = installed;
    }

    void take(List<SharedChange> told) {
        boolean news = false;
        for (SharedChange change : told) {
            if (!this.waiting.containsKey(change.mod()) && this.waiting.size() >= SharedChange.MOST) {
                this.waiting.values().removeIf(known -> !this.differs(known));
            }
            if (this.waiting.containsKey(change.mod()) || this.waiting.size() < SharedChange.MOST) {
                news |= !change.equals(this.waiting.put(change.mod(), change)) && this.differs(change);
            }
        }
        if (!news) {
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
        return this.waiting.values().stream().filter(this::differs).toList();
    }

    void clear() {
        this.waiting.clear();
    }

    interface Confirm {
        void ask(List<String> lines, Runnable yes, Runnable no);
    }

    void match(List<SharedChange> shown, Confirm confirm) {
        ChangeRequest request = new ChangeRequest();
        List<String> adds = new ArrayList<>();
        List<SharedChange> waiting = this.getWaiting();
        for (SharedChange change : shown) {
            if (!waiting.contains(change)) {
                continue;
            }
            request = switch (change.kind()) {
                case ADD -> request.add(change.project(), change.version());
                case ON -> request.on(change.mod());
                case OFF -> request.off(change.mod());
            };
            if (change.kind() == SharedChange.Kind.ADD) {
                adds.add(change.mod());
            }
        }

        this.apply(request, adds, confirm, this::clear);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void apply(ChangeRequest request, List<String> adds, Confirm confirm, Runnable onOtherMod) {
        if (request.equals(new ChangeRequest())) {
            return;
        }

        EngineApi.onGame(ApiChanges.plan(request, new CallerBlocks.Callers(List.of(), true)).thenApply(
                plan -> Map.entry(plan, !plan.canApply() || ApiChanges.findAddedIds(plan).containsAll(adds))))
                .thenCompose(planned -> {
                    if (planned.getValue() && (adds.isEmpty() || !planned.getKey().canApply())) {
                        return planned.getKey().accept();
                    }
                    if (planned.getValue()) {
                        CompletableFuture<Boolean> said = new CompletableFuture<>();
                        confirm.ask(planned.getKey().getLines(), () -> said.complete(true),
                                () -> said.complete(false));
                        return said.thenCompose(yes -> {
                            if (yes) {
                                return planned.getKey().accept();
                            }
                            planned.getKey().cancel();
                            return CompletableFuture.completedFuture(SwitchResult.ofRefusal(Reason.REFUSED,
                                    Lang.get("hotwire.host.not_matched")));
                        });
                    }
                    planned.getKey().cancel();
                    onOtherMod.run();
                    return CompletableFuture.completedFuture(SwitchResult.ofRefusal(Reason.REFUSED,
                            Lang.get("hotwire.host.other_mod")));
                }).thenAccept(result -> this.engine.showMessage(result.message(),
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
