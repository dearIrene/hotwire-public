package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.ui.list.ModListModel;
import dev.moon.hotwire.ui.list.ModStatus;
import java.util.List;

final class ProfileHost implements ProfileMenu.Screen {
    private final ModScreen screen;
    private final ModScreen.Game game;
    private final ModListModel model;

    ProfileHost(ModScreen screen, ModScreen.Game game, ModListModel model) {
        this.screen = screen;
        this.game = game;
        this.model = model;
    }

    @Override
    public List<String> getRelevantIds() {
        return this.model.filterRelevant(this.game.getMods().stream().map(ModStatus::id).toList());
    }

    @Override
    public boolean isOn(String id) {
        return this.game.getCatalog().isOn(id);
    }

    @Override
    public void showMessage(String text, boolean bad) {
        this.screen.showMessage(text, bad);
    }

    @Override
    public void refilter() {
        this.screen.refilter();
    }

    @Override
    public void applyProfile(String name, ProfileStore.ProfilePlan plan) {
        this.game.applyProfile(name, plan);
    }
}
