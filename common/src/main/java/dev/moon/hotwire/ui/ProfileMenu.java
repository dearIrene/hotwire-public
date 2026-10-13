package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

final class ProfileMenu {
    static final String UNSAVED = "This setup (unsaved)";

    private sealed interface Row {
        record Heading() implements Row {
        }

        record Apply(String name) implements Row {
        }

        record Save() implements Row {
        }

        record Rename(String name) implements Row {
        }
    }

    interface Screen {
        List<String> getRelevantIds();

        boolean isOn(String id);

        void showMessage(String text, boolean bad);

        void refilter();

        void applyProfile(String name, ProfileStore.ProfilePlan plan);
    }

    final Dropdown menu;
    final TextField naming;
    private final ProfileStore profiles;
    private final Screen screen;
    private final Root root;
    private final TextField search;
    private final Element showMenu;
    private List<Row> rows = List.of();
    private @Nullable String renaming;
    private boolean nameMode;

    ProfileMenu(ProfileStore profiles, Screen screen, Root root, TextField search, Element showMenu) {
        this.profiles = profiles;
        this.screen = screen;
        this.root = root;
        this.search = search;
        this.showMenu = showMenu;
        this.menu = new Dropdown(root.getMenus(), UNSAVED, this::onPicked, this::onDeleted);
        this.naming = new TextField("Name this setup, then press Enter", text -> { }, this::onNamed, this::stopNaming);
        this.naming.setVisible(false);
    }

    boolean isNaming() {
        return this.nameMode;
    }

    void refresh(List<String> relevant, Predicate<String> on) {
        List<Dropdown.Item> items = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        String current = null;
        List<String> names = this.profiles.getNames();
        if (names.isEmpty()) {
            items.add(new Dropdown.Item("No saved setups yet", null, true, false));
            rows.add(new Row.Heading());
        }
        for (String name : names) {
            ProfileStore.ProfilePlan plan = this.profiles.planFor(name, relevant, on);
            if (plan.isEmpty() && current == null) {
                current = name;
            }
            items.add(new Dropdown.Item(name, name.equals(current) ? "current" : plan.describe(), false, true));
            rows.add(new Row.Apply(name));
        }
        items.add(Dropdown.Item.of("Save this setup..."));
        rows.add(new Row.Save());
        if (current != null) {
            items.add(Dropdown.Item.of("Rename '" + current + "'..."));
            rows.add(new Row.Rename(current));
        }
        this.rows = rows;
        this.menu.setItems(items);
        this.menu.setHeader(current == null ? UNSAVED : current);
    }

    private void onPicked(int index) {
        switch (this.rows.get(index)) {
            case Row.Heading heading -> { }
            case Row.Save save -> this.startNaming(null);
            case Row.Rename rename -> this.startNaming(rename.name());
            case Row.Apply apply -> this.apply(apply.name());
        }
    }

    private void apply(String name) {
        ProfileStore.ProfilePlan plan = this.profiles.planFor(name, this.screen.getRelevantIds(), this.screen::isOn);
        if (plan.isEmpty()) {
            this.screen.showMessage("'" + name + "' is already what is loaded.", false);
            return;
        }
        this.screen.applyProfile(name, plan);
    }

    private void onDeleted(int index) {
        if (!(this.rows.get(index) instanceof Row.Apply(String name))) {
            return;
        }
        try {
            this.profiles.delete(name);
            this.screen.showMessage("Deleted '" + name + "'. Nothing in the game changed.", false);
        } catch (IOException e) {
            this.screen.showMessage("Could not delete '" + name + "': " + e.getMessage(), true);
        }
        this.screen.refilter();
    }

    private void startNaming(@Nullable String renaming) {
        this.renaming = renaming;
        this.nameMode = true;
        this.menu.setVisible(false);
        this.showMenu.setVisible(false);
        this.naming.setVisible(true);
        this.naming.setText(renaming == null ? "" : renaming);
        this.root.focus(this.naming);
        this.screen.showMessage(renaming == null ? "Name this setup, then press Enter. Escape to cancel."
                : "New name for '" + renaming + "'. Enter to save, Escape to cancel.", false);
    }

    private void stopNaming() {
        this.nameMode = false;
        this.naming.setVisible(false);
        this.menu.setVisible(true);
        this.showMenu.setVisible(true);
        this.root.focus(this.search);
        this.screen.showMessage("", false);
    }

    private void onNamed() {
        String name = this.naming.getText().strip();
        if (name.isEmpty()) {
            return;
        }
        String from = this.renaming;
        this.stopNaming();
        try {
            if (from == null) {
                Set<String> on = new LinkedHashSet<>();
                this.screen.getRelevantIds().stream().filter(this.screen::isOn).forEach(on::add);
                this.profiles.save(name, on);
                this.screen.showMessage("Saved '" + name + "' with " + on.size() + " mods on.", false);
            } else if (this.profiles.rename(from, name)) {
                this.screen.showMessage("Renamed to '" + name + "'.", false);
            } else {
                this.screen.showMessage("Could not rename - there is already a setup called '" + name + "'.", true);
            }
        } catch (IOException e) {
            this.screen.showMessage("Could not save '" + name + "': " + e.getMessage(), true);
        }
        this.screen.refilter();
    }
}
