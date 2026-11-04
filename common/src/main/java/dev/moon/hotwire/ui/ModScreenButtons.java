package dev.moon.hotwire.ui;

import dev.moon.hotwire.ui.list.ModListModel.Show;
import dev.moon.hotwire.ui.list.ModStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ModScreenButtons {
    private final ModScreen owner;

    ModScreenButtons(ModScreen owner) {
        this.owner = owner;
    }

    void closePanels() {
        this.owner.panels.stream().filter(panel -> panel != this.owner.progress && panel != this.owner.firstOpen)
                .forEach(Panel::dismiss);
    }

    List<String> getPickedThatAre(boolean on) {
        List<String> out = new ArrayList<>();
        for (String id : this.owner.model.getPicked()) {
            if (this.owner.game.getCatalog().isOn(id) == on) {
                out.add(id);
            }
        }
        return out;
    }

    void refreshButtons() {
        boolean idle = this.owner.isIdle();
        int off = this.getPickedThatAre(true).size();
        int on = this.getPickedThatAre(false).size();
        String label;
        String shortLabel;
        if (off > 0 && on > 0) {
            label = Lang.get("hotwire.screen.switch_count", off + on);
            shortLabel = label;
        } else if (on > 0) {
            label = Lang.get("hotwire.screen.turn_on_count", on);
            shortLabel = Lang.get("hotwire.screen.on_count", on);
        } else {
            label = off > 0 ? Lang.get("hotwire.screen.turn_off_count", off) : Lang.get("hotwire.screen.turn_off");
            shortLabel = off > 0 ? Lang.get("hotwire.screen.off_count", off) : Lang.get("hotwire.screen.off");
        }
        if (!label.equals(this.owner.toggleLabel)) {
            this.owner.toggleLabel = label;
            this.owner.toggle.label(label, shortLabel);
            this.owner.toggle.setPrimary(on > 0 && off == 0);
            this.owner.bar.markStale();
        }
        this.owner.pending.setBadge(this.owner.pendingCount);
        CacheFillPanel.markButton(this.owner.switchCache, this.owner.bar, this.owner.game.getCaches().getCacheFill());
        this.owner.toggle.setActive(idle && off + on > 0);
        this.owner.add.setActive(idle && this.owner.game.canAdd());
        this.owner.report.setActive(idle);
        this.owner.pending.setActive(idle);
        this.owner.updates.setActive(idle && this.owner.game.canUpdate());
        this.owner.advanced.setActive(idle);
    }

    void switchPicked() {
        this.switchPicked(false);
        this.switchPicked(true);
    }

    void switchPicked(boolean on) {
        List<String> ids = this.getPickedThatAre(!on);
        if (ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            this.owner.list.setWish(id, on);
        }
        this.owner.model.clear();
        if (on) {
            this.owner.game.turnOn(ids);
        } else {
            this.owner.turnOffAsking(ids);
        }
    }

    void onSearched(String text) {
        if (this.owner.model.setFilter(text)) {
            this.owner.list.scrollToTop();
            this.refilter();
        }
    }

    void refilter() {
        Map<String, ModStatus> byId = new HashMap<>();
        List<String> all = new ArrayList<>();
        for (ModStatus mod : this.owner.game.getMods()) {
            byId.put(mod.id(), mod);
            all.add(mod.id());
        }
        List<String> relevant = this.owner.model.filterRelevant(all);
        int on = (int) relevant.stream().filter(id -> this.owner.game.getCatalog().isOn(id)).count();
        this.owner.header.setCounts(on, relevant.size());
        List<String> grouped = this.owner.model.group(this.owner.model.applyFilter(relevant));
        this.owner.shown.clear();
        this.owner.childOf.clear();
        for (String id : grouped) {
            this.owner.shown.add(Objects.requireNonNull(byId.get(id)));
            String owner = this.owner.model.findGroupedUnder(id);
            if (owner != null) {
                this.owner.childOf.put(id, owner);
            }
        }
        this.owner.list.onChanged();
        this.owner.profiles.refresh(relevant, this.owner.game.getCatalog()::isOn);
        this.refreshShowMenu();
    }

    void refreshShowMenu() {
        this.owner.show.setChoices(getShowChoices(this.owner.model.getPicked().size()));
        this.owner.show.setChosen(ModScreen.SHOWS.indexOf(this.owner.model.getShowing()));

        this.owner.libraries.setPrimary(this.owner.model.isShowingLibraries());
    }

    static List<ChoiceBar.Choice> getShowChoices(int picked) {
        List<ChoiceBar.Choice> choices = new ArrayList<>();
        for (Show choice : ModScreen.SHOWS) {
            choices.add(new ChoiceBar.Choice(choice.getLabel(), choice == Show.SELECTED && picked > 0
                    ? String.valueOf(picked) : null, ChoiceBar.Glyph.NONE));
        }
        return choices;
    }
}
