package dev.moon.hotwire.ui.pages;

import dev.moon.hotwire.seam.PlayerMods;
import dev.moon.hotwire.seam.SharedMod;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreenGame;
import dev.moon.hotwire.ui.list.ModCatalog;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Colors;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Part;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Stripe;
import dev.moon.hotwire.ui.widgets.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class PlayersPage {
    private static final int CARD_TALL = 30;
    private static final int FACE = 18;
    private static final int BIG_FACE = 26;
    private static final int PEOPLE_WIDE = 150;
    private static final int ROW_TALL = 18;

    private static final int OWN_EVERY = 30;

    private enum Kind { ADD, ON, OFF, VERSION, NOT_ON_MODRINTH }

    private record Difference(Kind kind, SharedMod theirs, @Nullable SharedMod mine) {
    }

    public final Part part;
    private final ModScreenGame game;
    private final Line people = Kit.column().gap(4);
    private final Line rows = Kit.column().gap(3);
    private final Text heading = Kit.text("");
    private final Text summary = Kit.dim("");
    private final Text empty = Kit.dim(Lang.get("hotwire.players.nobody")).wrap();
    private final Push matchAll = Kit.lead("", this::matchAll);
    private @Nullable UUID chosen;
    private List<PlayerMods> shown = List.of();
    private Map<String, SharedMod> own = Map.of();
    private @Nullable List<SharedMod> ownSeen;

    private int frames = OWN_EVERY;

    public PlayersPage(ModScreenGame game) {
        this.game = game;
        Part header = Kit.row(new Face(), Kit.column(this.heading, this.summary).gap(3), Kit.space(), this.matchAll)
                .gap(8).cross(Line.Cross.CENTER);
        Part detail = Kit.column(header, Kit.scroll(this.rows).grow(1)).gap(10).grow(1);
        this.part = Kit.column(
                Kit.row(Kit.text(Lang.get("hotwire.players.title")).big(), Kit.space(),
                        Kit.dim(Lang.get("hotwire.players.share")),
                        Kit.check(game::isSharingMods, () -> game.setSharingMods(!game.isSharingMods()))).gap(8),
                this.empty,
                Kit.row(Kit.scroll(this.people).width(PEOPLE_WIDE), Kit.rule().width(1), detail).gap(10).grow(1)
                        .cross(Line.Cross.STRETCH))
                .pad(12, 10).gap(10).grow(1);
    }

    public void refresh() {
        List<PlayerMods> players = this.game.findPlayers();
        boolean ownDue = ++this.frames >= OWN_EVERY;
        if (ownDue) {
            this.frames = 0;
            List<SharedMod> mine = this.game.findOwnMods();
            ownDue = !mine.equals(this.ownSeen);
            if (ownDue) {
                this.ownSeen = mine;
                Map<String, SharedMod> byId = new HashMap<>();
                mine.forEach(mod -> byId.put(mod.mod(), mod));
                this.own = byId;
            }
        }
        if (!ownDue && players.equals(this.shown)) {
            return;
        }
        this.shown = players;
        if (this.chosen == null || players.stream().noneMatch(one -> one.player().equals(this.chosen))) {
            this.chosen = players.isEmpty() ? null : players.getFirst().player();
        }
        this.empty.show(players.isEmpty());
        this.people.clear();
        for (PlayerMods player : players) {
            this.people.with(new Card(player, player.countDiffering(this.own)));
        }
        this.fillDetail();
    }

    private void choose(UUID player) {
        this.chosen = player;
        this.fillDetail();
    }

    private @Nullable PlayerMods findChosen() {
        return this.shown.stream().filter(one -> one.player().equals(this.chosen)).findFirst().orElse(null);
    }

    private void fillDetail() {
        this.rows.clear();
        PlayerMods player = this.findChosen();
        if (player == null) {
            this.heading.set("");
            this.summary.set("");
            this.matchAll.show(false);
            return;
        }
        this.heading.set(player.name());
        if (!player.sharing()) {
            this.summary.set(Lang.get("hotwire.players.not_sharing", player.name()));
            this.matchAll.show(false);
            return;
        }
        List<Difference> differences = this.findDifferences(player);
        long doable = differences.stream().filter(one -> one.kind() != Kind.VERSION
                && one.kind() != Kind.NOT_ON_MODRINTH).count();
        this.summary.set(differences.isEmpty() ? Lang.get("hotwire.players.same", player.name())
                : Lang.get("hotwire.players.differ", player.name(), differences.size()));
        this.matchAll.label(Lang.get("hotwire.players.match_all", doable)).show(doable > 1);
        for (Kind kind : Kind.values()) {
            List<Difference> ofKind = differences.stream().filter(one -> one.kind() == kind).toList();
            if (ofKind.isEmpty()) {
                continue;
            }
            this.rows.with(Kit.row(Kit.text(Lang.get(findGroupKey(kind)) + "  " + ofKind.size(), findInk(kind)))
                    .pad(0, 4));
            ofKind.forEach(one -> this.rows.with(this.makeRow(one)));
        }
    }

    private static String findGroupKey(Kind kind) {
        return switch (kind) {
            case ADD -> "hotwire.players.group.add";
            case ON -> "hotwire.players.group.on";
            case OFF -> "hotwire.players.group.off";
            case VERSION -> "hotwire.players.group.version";
            case NOT_ON_MODRINTH -> "hotwire.players.group.not_on_modrinth";
        };
    }

    private static int findInk(Kind kind) {
        return switch (kind) {
            case ADD -> Palette.GOOD;
            case ON -> Palette.ON;
            case OFF -> Palette.OFF_TINT;
            case VERSION -> Palette.GOLD;
            case NOT_ON_MODRINTH -> Palette.FAINT;
        };
    }

    private Part makeRow(Difference one) {
        ModCatalog catalog = this.game.getCatalog();
        String id = one.theirs().mod();
        String name = one.mine() == null ? id : catalog.name(id);
        int ink = findInk(one.kind());
        Line row = Kit.row().gap(8).pad(6, 4).cross(Line.Cross.CENTER).face(Palette.HAIRLINE);
        row.with(new Stripe(ink, ROW_TALL), Kit.picture(() -> this.game.findIcon(id), name, 16), Kit.text(name));
        SharedMod mine = one.mine();
        String versions = mine != null && !mine.version().equals(one.theirs().version())
                ? Lang.get("hotwire.players.versions", mine.version(), one.theirs().version())
                : one.theirs().version();
        row.with(Kit.dim(versions).spare(), Kit.space());
        String action = switch (one.kind()) {
            case ADD -> Lang.get("hotwire.players.add");
            case ON -> Lang.get("hotwire.players.turn_on");
            case OFF -> Lang.get("hotwire.players.turn_off");
            case VERSION, NOT_ON_MODRINTH -> "";
        };
        if (!action.isEmpty()) {
            row.with(Kit.push(action, () -> this.game.matchPlayer(List.of(one.theirs()))).ink(ink));
        }
        return row;
    }

    private void matchAll() {
        PlayerMods player = this.findChosen();
        if (player == null) {
            return;
        }
        List<SharedMod> all = new ArrayList<>();
        for (Difference one : this.findDifferences(player)) {
            if (one.kind() == Kind.ADD || one.kind() == Kind.ON || one.kind() == Kind.OFF) {
                all.add(one.theirs());
            }
        }
        if (!all.isEmpty()) {
            this.game.matchPlayer(all);
        }
    }

    private List<Difference> findDifferences(PlayerMods player) {
        List<Difference> found = new ArrayList<>();
        for (SharedMod theirs : player.mods()) {
            SharedMod mine = this.own.get(theirs.mod());
            if (mine == null) {
                found.add(new Difference(theirs.project().isEmpty() ? Kind.NOT_ON_MODRINTH : Kind.ADD, theirs, null));
            } else if (theirs.on() != mine.on()) {
                found.add(new Difference(theirs.on() ? Kind.ON : Kind.OFF, theirs, mine));
            } else if (!theirs.version().equals(mine.version())) {
                found.add(new Difference(Kind.VERSION, theirs, mine));
            }
        }
        return found;
    }

    private static void drawFace(Canvas canvas, UUID player, int x, int y, int side, float alpha) {
        if (!canvas.drawFace(player, x, y, side)) {
            canvas.fill(x, y, x + side, y + side, Colors.fade(Palette.PANEL_EDGE, alpha));
        }
    }

    private final class Face extends Part {
        Face() {
            this.width(BIG_FACE);
            this.height(BIG_FACE);
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            UUID player = PlayersPage.this.chosen;
            if (player != null) {
                drawFace(canvas, player, this.getX(), this.getY(), BIG_FACE, alpha);
            }
        }
    }

    private final class Card extends Part {
        private final PlayerMods player;
        private final String note;
        private final int noteInk;

        Card(PlayerMods player, int differing) {
            this.player = player;
            String said = Lang.get("hotwire.players.card_differ", differing);
            int ink = Palette.WARN;
            if (!player.sharing()) {
                said = Lang.get("hotwire.players.card_hidden");
                ink = Palette.FAINT;
            } else if (differing == 0) {
                said = Lang.get("hotwire.players.card_same");
                ink = Palette.GOOD;
            }
            this.note = said;
            this.noteInk = ink;
            this.height(CARD_TALL);
            this.press((button, modifiers) -> PlayersPage.this.choose(player.player()));
        }

        @Override
        protected void paint(Canvas canvas, float alpha) {
            boolean picked = this.player.player().equals(PlayersPage.this.chosen);
            int x = this.getX();
            int y = this.getY();
            int right = x + this.getWidth();
            int bottom = y + this.getHeight();
            int edge = picked ? Palette.ACCENT : this.isHovered() ? Palette.DIM : Palette.PANEL_EDGE;
            canvas.fill(x + 1, y + 1, right - 1, bottom - 1, Colors.fade(Palette.HAIRLINE, alpha));
            canvas.fill(x, y, right, y + 1, Colors.fade(edge, alpha));
            canvas.fill(x, bottom - 1, right, bottom, Colors.fade(edge, alpha));
            canvas.fill(x, y, x + 1, bottom, Colors.fade(edge, alpha));
            canvas.fill(right - 1, y, right, bottom, Colors.fade(edge, alpha));
            drawFace(canvas, this.player.player(), x + 6, y + (CARD_TALL - FACE) / 2, FACE, alpha);
            int textX = x + 6 + FACE + 6;
            int room = right - 6 - textX;
            canvas.drawText(canvas.clip(this.player.name(), room), textX, y + 6, Colors.fade(Palette.TEXT, alpha));
            canvas.drawText(canvas.clip(this.note, room), textX, y + 17, Colors.fade(this.noteInk, alpha));
        }
    }
}
