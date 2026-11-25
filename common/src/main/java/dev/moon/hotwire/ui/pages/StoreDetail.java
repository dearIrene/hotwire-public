package dev.moon.hotwire.ui.pages;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Canvas;
import dev.moon.hotwire.ui.widgets.Drawer;
import dev.moon.hotwire.ui.widgets.Element;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Markdown;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Part;
import dev.moon.hotwire.ui.widgets.Popup;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Scroll;
import dev.moon.hotwire.ui.widgets.StoreIcons;
import dev.moon.hotwire.ui.widgets.Tabs;
import dev.moon.hotwire.ui.widgets.Text;
import dev.moon.hotwire.ui.widgets.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class StoreDetail {
    static final int WIDTH = 220;

    private static final int MOST_VERSIONS = 60;
    private static final int NOTES_TALL = 120;
    private static final int ICON = 28;

    private static final int ADDRESS_LINE = 60;
    private static final int ABOUT = 0;

    public final Drawer part;
    private final StoreModel model;
    private final StoreIcons icons;
    private final StorePage.Game game;
    private final FewDownloadsSheet few;
    private final Popup popup;

    private final Supplier<Part> bounds;
    private final Markdown words;
    private final Line header = Kit.row().gap(8);
    private final Text title = Kit.text("").lines(2);
    private final Text sub = Kit.dim("").lines(2);
    private final Text link = Kit.text("", Palette.PATH);
    private final Line actions = Kit.row().gap(4);
    private final Tabs tabs = Kit.tabs(Tabs.Look.WORDS, this::showTab);
    private final Line about;
    private final Line versions = Kit.column().gap(6);
    private final Line shownTab = Kit.column();
    private final Scroll page;
    private final Push download = Kit.push("↓", this::openDownloads);

    private String openedVersion = "";
    private ModrinthClient.@Nullable Hit hit;

    private volatile ModrinthClient.@Nullable Project project;
    private volatile @Nullable List<ModrinthClient.Version> found;
    private volatile @Nullable String failure;
    private volatile boolean arrived;
    private String state = "";

    StoreDetail(StoreModel model, StoreIcons icons, StorePage.Game game, Popup popup, FewDownloadsSheet few,
            Supplier<Part> bounds) {
        this.model = model;
        this.icons = icons;
        this.game = game;
        this.popup = popup;
        this.few = few;
        this.bounds = bounds;
        this.words = new Markdown(icons).onLink(this::askToOpenFromPage);

        Line linkRow = Kit.row(this.link);
        linkRow.press((button, modifiers) -> this.askToOpen(linkRow, this.link.get()));
        this.about = Kit.column(linkRow, this.words).gap(8);
        this.tabs.tab(Lang.get("hotwire.store.about")).tab(Lang.get("hotwire.store.versions_short"));
        this.page = Kit.scroll(this.shownTab);
        this.words.within(this.page);
        Line body = Kit.column(this.header, this.actions, this.tabs, this.page.grow(1)).pad(10, 10).gap(8)
                .face(Palette.PANEL);
        this.part = Kit.drawer(false, Kit.row(Kit.rule().width(1), new Body(body).grow(1))
                .cross(Line.Cross.STRETCH), WIDTH);
        this.showTab(ABOUT);
    }

    void open(ModrinthClient.Hit hit) {
        this.hit = hit;
        this.project = null;
        this.found = null;
        this.failure = null;
        this.state = "";
        this.openedVersion = "";
        this.title.set(hit.title());
        this.header.clear().with(Kit.picture(() -> hit.iconUrl().isEmpty() ? null
                : this.icons.find(hit.projectId(), hit.iconUrl()), hit.title(), ICON),
                Kit.column(this.title, this.sub).gap(3).grow(1),
                Kit.push("×", this::dismiss));
        this.showTab(ABOUT);
        this.fill();
        this.part.open(true);
        this.model.findProject(hit, page -> this.take(() -> this.project = page), why -> this.take(
                () -> this.failure = why));
        this.model.findVersions(hit, versions -> this.take(() -> this.found = versions.size() > MOST_VERSIONS
                ? List.copyOf(versions.subList(0, MOST_VERSIONS)) : versions), why -> this.take(
                        () -> this.failure = why));
    }

    public boolean isOpen() {
        return this.part.isOpen();
    }

    boolean isShowing(String projectId) {
        ModrinthClient.Hit shown = this.hit;
        return this.isOpen() && shown != null && shown.projectId().equals(projectId);
    }

    public void dismiss() {
        this.part.open(false);
    }

    private void showTab(int tab) {
        this.tabs.choose(tab);
        this.shownTab.clear().with(tab == ABOUT ? this.about : this.versions);
        this.page.reset();
    }

    private void take(Runnable answer) {
        answer.run();
        this.arrived = true;
    }

    private void openOnModrinth() {
        ModrinthClient.Hit shown = this.hit;
        if (shown != null) {
            this.game.openLink("https://modrinth.com/project/" + (shown.slug().isEmpty() ? shown.projectId()
                    : shown.slug()));
        }
    }

    private void openMenu(Element at, List<Popup.Item> items) {
        Part within = this.bounds.get();
        this.popup.openAt(at.getPointerX(), at.getPointerY(), within.getX() + within.getWidth(),
                within.getY() + within.getHeight(), items);
    }

    private void askToOpen(Element at, String url) {
        List<Popup.Item> items = new ArrayList<>();
        for (int from = 0; from < url.length(); from += ADDRESS_LINE) {
            items.add(new Popup.Item(url.substring(from, Math.min(url.length(), from + ADDRESS_LINE)), Palette.DIM,
                    null));
        }
        items.add(new Popup.Item(Lang.get("hotwire.store.open_link"), Palette.TEXT, () -> this.game.openLink(url)));
        this.openMenu(at, items);
    }

    private void askToOpenFromPage(String url) {
        this.askToOpen(this.words, url);
    }

    private void openDownloads() {
        ModrinthClient.Hit shown = this.hit;
        if (shown != null) {
            ModrinthClient.Version update = this.model.getUpdates().get(shown.projectId());
            this.openMenu(this.download, StorePage.makeDownloads(this.model, this.game, shown,
                    update == null ? "" : update.id(), this.few));
        }
    }

    private String findState(ModrinthClient.Hit shown) {
        String id = shown.projectId();
        if (this.model.isShowingPacks()) {
            return this.model.findPackState(id).name();
        }
        if (!this.model.canAdd()) {
            return "only";
        }
        if (this.model.isQueuedProject(id)) {
            return "queued";
        }
        StoreModel.Card card = this.model.getCardsByProject().get(id);
        boolean installed = this.model.isInstalledProject(id) || (card != null && card.installed());
        if (this.model.getUpdates().containsKey(id)) {
            return "update";
        }
        return installed ? "installed" : "add";
    }

    private Push makeLead(ModrinthClient.Hit shown, String state) {
        String id = shown.projectId();
        ModrinthClient.Version update = this.model.getUpdates().get(id);
        Runnable add = () -> this.few.run(shown, () -> this.game.addFromStore(id, update == null ? ""
                : update.id(), shown.title()));
        return switch (state) {
            case "add" -> Kit.lead(Lang.get("hotwire.store.add"), add);
            case "update" -> Kit.lead(Lang.get("hotwire.store.update"), add);
            case "MISSING" -> Kit.lead(Lang.get("hotwire.store.download"), add);
            case "ON" -> Kit.lead(Lang.get("hotwire.store.pack.turn_off"), () -> this.model.togglePack(id));
            case "OFF" -> Kit.lead(Lang.get("hotwire.store.pack.turn_on"), () -> this.model.togglePack(id));
            default -> Kit.lead(Lang.get(switch (state) {
                case "only" -> "hotwire.store.download_only";
                case "queued" -> "hotwire.store.queued";
                case "DOWNLOADING" -> "hotwire.store.pack.downloading";
                case "NEEDS_WORLD" -> "hotwire.store.pack.needs_world";
                default -> "hotwire.store.installed";
            }), () -> { }).active(false);
        };
    }

    private void fill() {
        ModrinthClient.Hit shown = this.hit;
        if (shown == null) {
            return;
        }
        ModrinthClient.Project page = this.project;
        String license = page == null || page.license().isEmpty() ? ""
                : "  " + Lang.get("hotwire.store.license", page.license());
        this.sub.set(shown.author() + license + "  " + StorePage.describeDownloads(page == null ? shown.downloads()
                : page.downloads()) + " " + Lang.get("hotwire.store.downloads"));
        this.link.set(page == null ? "" : page.sourceUrl()).show(page != null && !page.sourceUrl().isEmpty());
        this.words.set(page == null ? shown.description() : page.body());
        List<ModrinthClient.Version> known = this.found;
        String why = this.failure;
        this.versions.clear();
        if (known == null) {
            this.versions.with(Kit.text(why == null ? Lang.get("hotwire.store.loading")
                    : Lang.get("hotwire.store.failed", why), why == null ? Palette.FAINT : Palette.BAD).wrap());
        } else {
            known.forEach(version -> {
                this.versions.with(this.makeVersion(shown, version));
                if (version.id().equals(this.openedVersion)) {
                    this.versions.with(Kit.row(new Part().width(6), (version.changelog().isBlank()
                            ? Kit.dim(Lang.get("hotwire.updates.no_changelog"))
                            : Kit.scroll(new Markdown().set(version.changelog())).capAt(NOTES_TALL)).grow(1)));
                }
            });
        }
        this.state = this.findState(shown);
        this.actions.clear().with(this.makeLead(shown, this.state), this.download.show(!this.model.isShowingPacks()),
                Kit.space(), Kit.push(Lang.get("hotwire.store.modrinth"), this::openOnModrinth));
    }

    private Part makeVersion(ModrinthClient.Hit shown, ModrinthClient.Version version) {
        String name = version.number() + (version.name().isEmpty() || version.name().equals(version.number()) ? ""
                : "  " + version.name());
        String needs = version.requiredProjects().isEmpty() ? ""
                : Lang.get("hotwire.store.needs", version.requiredProjects().size());

        String type = "release".equals(version.type()) ? "" : version.type();
        String day = version.published().length() >= 10 ? version.published().substring(0, 10) : "";

        boolean has = this.model.isInstalledProject(shown.projectId());
        Part button = this.model.isInstalledVersion(version)
                ? Kit.text(Lang.get("hotwire.store.installed"), Palette.ON).spare()
                : Kit.push(Lang.get(has ? "hotwire.store.switch_this" : "hotwire.store.add_this"), () -> {
                    if (this.model.canAdd()) {
                        this.few.run(shown, () -> this.game.addFromStore(shown.projectId(), version.id(),
                                shown.title()));
                    } else {
                        this.few.run(shown, () -> this.game.saveFromStore(shown.projectId(), version.id(),
                                shown.title()));
                    }
                });
        boolean opened = version.id().equals(this.openedVersion);
        return Kit.column(Kit.row(Kit.text((opened ? "▾ " : "▸ ") + name).lines(1).grow(1), button)
                .gap(6).cross(Line.Cross.CENTER),
                Kit.row(Kit.text(type, Palette.WARN).spare(), Kit.text(day, Palette.FAINT).spare(),
                        Kit.text(needs, Palette.FAINT).spare()).gap(8)).gap(2).press((button2, modifiers) -> {
                            this.openedVersion = opened ? "" : version.id();
                            this.fill();
                        });
    }

    private final class Body extends Part {
        private final Line inner;

        Body(Line inner) {
            this.inner = this.add(inner);
        }

        @Override
        public void step(float dt) {
            ModrinthClient.Hit shown = StoreDetail.this.hit;
            if (shown != null && StoreDetail.this.isOpen() && (StoreDetail.this.arrived
                    || !StoreDetail.this.findState(shown).equals(StoreDetail.this.state))) {
                StoreDetail.this.arrived = false;
                StoreDetail.this.fill();
            }
            super.step(dt);
        }

        @Override
        public void measure(Canvas canvas, int room) {
            this.inner.measure(canvas, room);
            this.hugWidth = this.inner.findWidth();
            this.hugHeight = this.inner.findHeight();
        }

        @Override
        protected void place(Canvas canvas) {
            this.inner.lay(canvas, this.getX(), this.getY(), this.getWidth(), this.getHeight());
        }

        @Override
        public boolean onKey(int key, int modifiers) {
            if (key == TextField.ESCAPE && StoreDetail.this.isOpen()) {
                StoreDetail.this.dismiss();
                return true;
            }
            return false;
        }
    }
}
