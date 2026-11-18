package dev.moon.hotwire.ui.pages;

import dev.moon.hotwire.config.ProfileStore;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Root;
import dev.moon.hotwire.ui.widgets.Element;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Popup;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.TextField;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class ProfileMenu {
    public interface Screen {
        List<String> getRelevantIds();

        boolean isOn(String id);

        void showMessage(String text, boolean bad);

        void refilter();

        void applyProfile(String name, ProfileStore.ProfilePlan plan);
    }

    private final String unsaved;
    final Push menu;
    final TextField naming;
    private final ProfileStore profiles;
    private final Screen screen;
    private final Root root;
    private final TextField search;
    private final Element showMenu;
    private final Popup popup;
    private final Runnable manage;
    private List<Popup.Item> items = List.of();
    private boolean nameMode;

    ProfileMenu(ProfileStore profiles, Screen screen, Root root, TextField search, Element showMenu, Popup popup,
            Runnable manage) {
        this.profiles = profiles;
        this.unsaved = profiles.findInstanceName().orElseGet(() -> Lang.get("hotwire.setup.unsaved"));
        this.menu = Kit.push(this.unsaved, this::openMenu).menu();
        this.screen = screen;
        this.root = root;
        this.search = search;
        this.showMenu = showMenu;
        this.popup = popup;
        this.manage = manage;
        this.naming = new TextField(Lang.get("hotwire.setup.name_hint"), text -> { }, this::onNamed, this::stopNaming);
        this.naming.setVisible(false);
    }

    public boolean isNaming() {
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

        String noted = this.profiles.getCurrent();
        String current = noted != null && this.profiles.planFor(noted, relevant, on).isEmpty() ? noted : null;
        List<String> names = this.profiles.getNames();
        if (names.isEmpty()) {
            items.add(new Popup.Item(Lang.get("hotwire.setup.none"), Palette.FAINT, null));
        }
        for (String name : names) {
            ProfileStore.ProfilePlan plan = this.profiles.planFor(name, relevant, on);
            if (plan.isEmpty() && current == null) {
                current = name;
                this.note(name);
            }
            boolean now = name.equals(current);
            items.add(new Popup.Item(name + "  " + (now ? Lang.get("hotwire.setup.current") : plan.describe()),
                    now ? Palette.ON : Palette.TEXT, () -> this.apply(name)));
        }
        items.add(new Popup.Item(Lang.get("hotwire.setup.save"), Palette.TEXT, this::startNaming));
        items.add(new Popup.Item(Lang.get("hotwire.setup.manage"), Palette.TEXT, this.manage));
        this.items = items;
        this.menu.label(current == null ? this.unsaved : current);
    }

    private void apply(String name) {
        ProfileStore.ProfilePlan plan = this.profiles.planFor(name, this.screen.getRelevantIds(), this.screen::isOn);
        if (plan.isEmpty()) {
            this.note(name);
            this.screen.showMessage("'" + name + "' is already what is loaded.", false);
            this.screen.refilter();
            return;
        }
        this.screen.applyProfile(name, plan);
    }

    private void note(String name) {
        try {
            this.profiles.setCurrent(name);
        } catch (IOException e) {
            this.screen.showMessage(Lang.get("hotwire.profiles.failed", e.getMessage()), true);
        }
    }

    private void startNaming() {
        this.nameMode = true;
        this.menu.setVisible(false);
        this.showMenu.setVisible(false);
        this.naming.setVisible(true);
        this.naming.setText("");
        this.root.focus(this.naming);
        this.screen.showMessage(Lang.get("hotwire.setup.name_prompt"), false);
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
        this.stopNaming();
        try {
            Set<String> on = new LinkedHashSet<>();
            this.screen.getRelevantIds().stream().filter(this.screen::isOn).forEach(on::add);
            this.profiles.save(name, on);
            this.screen.showMessage(Lang.get("hotwire.setup.saved", name, on.size()), false);
        } catch (IOException e) {
            this.screen.showMessage(Lang.get("hotwire.setup.save_failed", name, e.getMessage()), true);
        }
        this.screen.refilter();
    }
}
