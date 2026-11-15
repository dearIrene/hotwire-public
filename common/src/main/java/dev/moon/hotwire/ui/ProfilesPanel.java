package dev.moon.hotwire.ui;

import dev.moon.hotwire.config.ProfileStore;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class ProfilesPanel extends Sheet {
    private static final long DOUBLE_NANOS = 350_000_000L;
    private static final int LIST_TALL = 132;

    private interface Change {
        void run() throws IOException;
    }

    private final ProfileStore profiles;
    private final ProfileMenu.Screen screen;

    private final Supplier<@Nullable String> joined;
    private final Runnable onClose;
    private final TextField naming = new TextField(Lang.get("hotwire.setup.name_hint"), text -> { }, this::rename,
            this::stopRenaming);
    private final TextField address = new TextField(Lang.get("hotwire.profiles.server_hint"), text -> { },
            this::addTyped, () -> { });
    private @Nullable String picked;
    private @Nullable String renaming;
    private @Nullable String deleting;
    private @Nullable String pressed;
    private long pressedAt;

    public ProfilesPanel(ProfileStore profiles, ProfileMenu.Screen screen, Supplier<@Nullable String> joined,
            Runnable onClose) {
        super(400, onClose);
        this.profiles = profiles;
        this.screen = screen;
        this.joined = joined;
        this.onClose = onClose;

        this.address.typeOnlyOnceClicked();
    }

    void present() {
        this.title.set(Lang.get("hotwire.profiles.title"));
        this.renaming = null;
        this.deleting = null;
        this.address.setText("");
        this.setSub(Lang.get("hotwire.profiles.hint"));
        this.fill();
        this.show();
    }

    private void fill() {
        List<String> names = this.profiles.getNames();
        if (this.picked == null || !names.contains(this.picked)) {
            this.picked = names.isEmpty() ? null : names.getFirst();
        }
        Line list = Kit.column().gap(2);
        names.forEach(name -> list.with(this.makeRow(name)));
        this.body.clear();
        if (names.isEmpty()) {
            this.body.with(Kit.dim(Lang.get("hotwire.setup.none")));
        } else {
            this.body.with(Kit.column(Kit.scroll(list).capAt(LIST_TALL)).pad(4, 4).face(Palette.HAIRLINE));
        }
        this.body.with(Kit.row(Kit.push(Lang.get("hotwire.profiles.new"), this::create), Kit.space()));
        String shown = this.picked;
        if (shown != null && this.renaming == null) {
            this.fillServers(shown);
        }
        this.buttons.clear().with(Kit.space());
        if (shown != null) {
            ProfileStore.ProfilePlan plan = this.findPlan(shown);
            this.buttons.with(this.setLead(Kit.lead(Lang.get("hotwire.profiles.apply", shown),
                    () -> this.apply(shown)).active(!plan.isEmpty())));
        }
        this.buttons.with(Kit.push(Lang.get("hotwire.button.done"), this.onClose));
    }

    private Part makeRow(String name) {
        boolean here = name.equals(this.picked);
        ProfileStore.ProfilePlan plan = this.findPlan(name);
        Part label = name.equals(this.renaming) ? Kit.fit(this.naming).height(Push.HEIGHT).grow(1)
                : Kit.text(name, here ? Palette.ACCENT : Palette.TEXT).grow(1);
        boolean sure = name.equals(this.deleting);
        Line row = Kit.row(label,
                Kit.text(plan.isEmpty() ? Lang.get("hotwire.setup.current") : plan.describe(), Palette.FAINT).spare(),
                Kit.push(Lang.get(sure ? "hotwire.profiles.delete_sure" : "hotwire.profiles.delete"),
                        () -> this.delete(name)).ink(Palette.BAD))
                .gap(8).pad(6, 3).cross(Line.Cross.CENTER);
        if (here) {
            row.face(Palette.TRACK);
        }
        return row.press((button, modifiers) -> this.press(name, button));
    }

    private void fillServers(String name) {
        this.body.with(Kit.rule(), Kit.text(Lang.get("hotwire.profiles.servers", name)),
                Kit.dim(Lang.get("hotwire.profiles.servers_line")).wrap());
        List<String> servers = this.profiles.getServersOf(name);
        for (String server : servers) {
            this.body.with(Kit.row(Kit.text(describe(server)).grow(1), Kit.push(
                    Lang.get("hotwire.profiles.remove"), () -> this.change(() -> this.profiles.removeServer(name,
                            server)))).gap(8).cross(Line.Cross.CENTER));
        }
        Line adding = Kit.row(Kit.fit(this.address).height(Push.HEIGHT).grow(1)).gap(6);
        String on = this.joined.get();
        if (on != null && !servers.contains(ProfileStore.normalize(on))) {
            adding.with(Kit.push(Lang.get("hotwire.profiles.add_joined", describe(on)), () -> this.add(on)));
        }
        this.body.with(adding);
    }

    private static String describe(String server) {
        return server.equals(ProfileStore.SINGLEPLAYER) ? Lang.get("hotwire.profiles.singleplayer") : server;
    }

    private ProfileStore.ProfilePlan findPlan(String name) {
        return this.profiles.planFor(name, this.screen.getRelevantIds(), this.screen::isOn);
    }

    private void press(String name, int button) {
        if (button != 0 || name.equals(this.renaming)) {
            return;
        }
        long now = System.nanoTime();
        boolean again = name.equals(this.pressed) && now - this.pressedAt < DOUBLE_NANOS;
        this.pressed = again ? null : name;
        this.pressedAt = now;
        this.picked = name;
        this.deleting = null;
        this.renaming = again ? name : null;
        if (again) {
            this.naming.setText(name);
        }
        this.fill();
    }

    private void create() {
        Set<String> on = new LinkedHashSet<>();
        this.screen.getRelevantIds().stream().filter(this.screen::isOn).forEach(on::add);
        List<String> names = this.profiles.getNames();
        String base = Lang.get("hotwire.profiles.new_name");
        String name = base;
        for (int number = 2; names.contains(name); number++) {
            name = base + " " + number;
        }
        String made = name;

        this.picked = made;
        this.renaming = made;
        this.naming.setText(made);
        this.change(() -> this.profiles.save(made, on));
    }

    private void rename() {
        String from = this.renaming;
        String wanted = this.naming.getText().strip();
        if (from == null || wanted.isEmpty() || wanted.equals(from)) {
            this.stopRenaming();
            return;
        }
        int at = this.profiles.getNames().indexOf(from);
        this.change(() -> {
            if (!this.profiles.rename(from, wanted)) {
                this.setSub(Lang.get("hotwire.setup.name_taken", wanted));
                return;
            }

            List<String> names = this.profiles.getNames();
            this.picked = at >= 0 && at < names.size() ? names.get(at) : null;
            this.renaming = null;
            this.setSub(Lang.get("hotwire.profiles.hint"));
        });
    }

    private void stopRenaming() {
        this.renaming = null;
        this.fill();
    }

    private void delete(String name) {
        if (!name.equals(this.deleting)) {
            this.deleting = name;
            this.renaming = null;
            this.fill();
            return;
        }
        this.deleting = null;
        this.change(() -> this.profiles.delete(name));
    }

    private void addTyped() {
        this.add(this.address.getText());
    }

    private void add(String server) {
        String to = this.picked;
        if (to == null || server.isBlank()) {
            return;
        }
        this.change(() -> {
            if (this.profiles.addServer(to, server)) {
                this.address.setText("");
                this.setSub(Lang.get("hotwire.profiles.hint"));
            } else {
                this.setSub(Lang.get("hotwire.profiles.bad_address"));
            }
        });
    }

    private void apply(String name) {
        ProfileStore.ProfilePlan plan = this.findPlan(name);
        this.onClose.run();
        if (!plan.isEmpty()) {
            this.screen.applyProfile(name, plan);
        }
    }

    private void change(Change change) {
        try {
            change.run();
        } catch (IOException e) {
            this.setSub(Lang.get("hotwire.profiles.failed", String.valueOf(e.getMessage())));
        }
        this.fill();
        this.screen.refilter();
    }
}
