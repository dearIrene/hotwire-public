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
    interface Screen {
        List<String> getRelevantIds();

        boolean isOn(String id);

        void showMessage(String text, boolean bad);

        void refilter();

        void applyProfile(String name, ProfileStore.ProfilePlan plan);
    }

    final Push menu = Kit.push(Lang.get("hotwire.setup.unsaved"), this::openMenu).menu();
    final TextField naming;
    private final ProfileStore profiles;
    private final Screen screen;
    private final Root root;
    private final TextField search;
    private final Element showMenu;
    private final Popup popup;
    private List<Popup.Item> items = List.of();
    private @Nullable String renaming;
    private boolean nameMode;

    ProfileMenu(ProfileStore profiles, Screen screen, Root root, TextField search, Element showMenu, Popup popup) {
        this.profiles = profiles;
        this.screen = screen;
        this.root = root;
        this.search = search;
        this.showMenu = showMenu;
        this.popup = popup;
        this.naming = new TextField(Lang.get("hotwire.setup.name_hint"), text -> { }, this::onNamed, this::stopNaming);
        this.naming.setVisible(false);
    }

    boolean isNaming() {
        return this.nameMode;
    }

    private void openMenu() {
        if (this.popup.isShowing(this.items)) {
            this.popup.dismiss();
            return;
        }
        Element base = this.root.getBase();
        this.popup.openAt(this.menu.getX(), this.menu.getY() + this.menu.getHeight() + 2, base.getWidth(),
                base.getHeight(), this.items);
    }

    void refresh(List<String> relevant, Predicate<String> on) {
        List<Popup.Item> items = new ArrayList<>();
        String current = null;
        List<String> names = this.profiles.getNames();
        if (names.isEmpty()) {
            items.add(new Popup.Item(Lang.get("hotwire.setup.none"), Palette.FAINT, null));
        }
        for (String name : names) {
            ProfileStore.ProfilePlan plan = this.profiles.planFor(name, relevant, on);
            if (plan.isEmpty() && current == null) {
                current = name;
            }
            boolean now = name.equals(current);
            items.add(new Popup.Item(name + "  " + (now ? Lang.get("hotwire.setup.current") : plan.describe()),
                    now ? Palette.ON : Palette.TEXT, () -> this.apply(name)));
        }
        items.add(new Popup.Item(Lang.get("hotwire.setup.save"), Palette.TEXT, () -> this.startNaming(null)));
        if (current != null) {
            String named = current;
            items.add(new Popup.Item(Lang.get("hotwire.setup.rename", named), Palette.TEXT,
                    () -> this.startNaming(named)));
        }
        names.forEach(name -> items.add(new Popup.Item(Lang.get("hotwire.setup.delete", name), Palette.BAD,
                () -> this.delete(name))));
        this.items = items;
        this.menu.label(current == null ? Lang.get("hotwire.setup.unsaved") : current);
    }

    private void apply(String name) {
        ProfileStore.ProfilePlan plan = this.profiles.planFor(name, this.screen.getRelevantIds(), this.screen::isOn);
        if (plan.isEmpty()) {
            this.screen.showMessage("'" + name + "' is already what is loaded.", false);
            return;
        }
        this.screen.applyProfile(name, plan);
    }

    private void delete(String name) {
        try {
            this.profiles.delete(name);
            this.screen.showMessage(Lang.get("hotwire.setup.deleted", name), false);
        } catch (IOException e) {
            this.screen.showMessage(Lang.get("hotwire.setup.delete_failed", name, e.getMessage()), true);
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
        this.screen.showMessage(renaming == null ? Lang.get("hotwire.setup.name_prompt")
                : Lang.get("hotwire.setup.rename_prompt", renaming), false);
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
                this.screen.showMessage(Lang.get("hotwire.setup.saved", name, on.size()), false);
            } else if (this.profiles.rename(from, name)) {
                this.screen.showMessage(Lang.get("hotwire.setup.renamed", name), false);
            } else {
                this.screen.showMessage(Lang.get("hotwire.setup.name_taken", name), true);
            }
        } catch (IOException e) {
            this.screen.showMessage(Lang.get("hotwire.setup.save_failed", name, e.getMessage()), true);
        }
        this.screen.refilter();
    }
}
