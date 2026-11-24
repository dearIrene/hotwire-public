package dev.moon.hotwire.ui.pages;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.widgets.Element;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Line;
import dev.moon.hotwire.ui.widgets.Markdown;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Part;
import dev.moon.hotwire.ui.widgets.Popup;
import dev.moon.hotwire.ui.widgets.Push;
import dev.moon.hotwire.ui.widgets.Scroll;
import dev.moon.hotwire.ui.widgets.Sheet;
import dev.moon.hotwire.ui.widgets.StoreIcons;
import dev.moon.hotwire.ui.widgets.Text;
import dev.moon.hotwire.ui.widgets.TextField;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class StoreDetail extends Sheet {
    private static final int MOST_VERSIONS = 60;
    private static final int NOTES_TALL = 120;
    private static final int PAGE_TALL = 250;

    private static final int ADDRESS_LINE = 60;

    private final StoreModel model;
    private final StoreIcons icons;
    private final StorePage.Game game;
    private final FewDownloadsSheet few;
    private final Popup popup;
    private final Markdown words;
    private final Text link = Kit.text("", Palette.PATH);
    private final Line versions = Kit.column().gap(2);

    private String openedVersion = "";
    private final Scroll page;
    private final Push download = Kit.push(Lang.get("hotwire.store.download"), this::openDownloads);
    private ModrinthClient.@Nullable Hit hit;

    private volatile ModrinthClient.@Nullable Project project;
    private volatile @Nullable List<ModrinthClient.Version> found;
    private volatile @Nullable String failure;
    private volatile boolean arrived;
    private String state = "";

    @SuppressWarnings("this-escape")
    StoreDetail(StoreModel model, StoreIcons icons, StorePage.Game game, Popup popup, FewDownloadsSheet few) {
        super(520, null);
        this.few = few;
        this.model = model;
        this.icons = icons;
        this.game = game;
        this.popup = popup;
        this.words = new Markdown(icons).onLink(this::askToOpenFromPage);

        Line linkRow = Kit.row(this.link);
        linkRow.press((button, modifiers) -> this.askToOpen(linkRow, this.link.get()));
        this.page = Kit.scroll(Kit.column(linkRow, this.words, Kit.rule(),
                Kit.dim(Lang.get("hotwire.store.versions")), this.versions).gap(8)).capAt(PAGE_TALL);
        this.words.within(this.page);
        this.body.with(this.page);
    }

    void open(ModrinthClient.Hit hit) {
        this.hit = hit;
        this.project = null;
        this.found = null;
        this.failure = null;
        this.state = "";
        this.title.set(hit.title());
        this.setMark(ModScreen.Icons.STORE, () -> hit.iconUrl().isEmpty() ? null
                : this.icons.find(hit.projectId(), hit.iconUrl()));
        this.page.reset();
        this.fill();
        this.show();
        this.model.findProject(hit, page -> this.take(() -> this.project = page), why -> this.take(
                () -> this.failure = why));
        this.model.findVersions(hit, versions -> this.take(() -> this.found = versions.size() > MOST_VERSIONS
                ? List.copyOf(versions.subList(0, MOST_VERSIONS)) : versions), why -> this.take(
                        () -> this.failure = why));
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

    private void askToOpen(Element at, String url) {
        List<Popup.Item> items = new ArrayList<>();
        for (int from = 0; from < url.length(); from += ADDRESS_LINE) {
            items.add(new Popup.Item(url.substring(from, Math.min(url.length(), from + ADDRESS_LINE)), Palette.DIM,
                    null));
        }
        items.add(new Popup.Item(Lang.get("hotwire.store.open_link"), Palette.TEXT, () -> this.game.openLink(url)));
        this.popup.openAt(at.getPointerX(), at.getPointerY(), this.getWidth(), this.getHeight(), items);
    }

    private void askToOpenFromPage(String url) {
        this.askToOpen(this.words, url);
    }

    private void openDownloads() {
        ModrinthClient.Hit shown = this.hit;
        if (shown != null) {
            ModrinthClient.Version update = this.model.getUpdates().get(shown.projectId());
            this.popup.openAt(this.download.getPointerX(), this.download.getPointerY(), this.getWidth(),
                    this.getHeight(), StorePage.makeDownloads(this.model, this.game, shown,
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
        this.setSub(shown.author() + license + "  " + StorePage.describeDownloads(page == null ? shown.downloads()
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
                    this.versions.with(Kit.row(new Part().width(10), (version.changelog().isBlank()
                            ? Kit.dim(Lang.get("hotwire.updates.no_changelog"))
                            : Kit.scroll(new Markdown().set(version.changelog())).capAt(NOTES_TALL)).grow(1))
                            .pad(0, 3));
                }
            });
        }
        this.state = this.findState(shown);
        this.buttons.clear().with(Kit.push(Lang.get("hotwire.store.open_modrinth"), this::openOnModrinth),
                Kit.space(), this.download.show(!this.model.isShowingPacks()),
                this.setLead(this.makeLead(shown, this.state)),
                Kit.push(Lang.get("hotwire.button.close"), this::dismiss));
    }

    private Part makeVersion(ModrinthClient.Hit shown, ModrinthClient.Version version) {
        String name = version.number() + (version.name().isEmpty() || version.name().equals(version.number()) ? ""
                : "  " + version.name());
        String needs = version.requiredProjects().isEmpty() ? ""
                : Lang.get("hotwire.store.needs", version.requiredProjects().size());

        String type = "release".equals(version.type()) ? "" : version.type();
        String day = version.published().length() >= 10 ? version.published().substring(0, 10) : "";

        boolean has = this.model.isInstalledProject(shown.projectId());
        return Kit.row(Kit.dim(name).grow(1), Kit.text(type, Palette.WARN).spare(),
                Kit.text(day, Palette.FAINT).spare(), Kit.text(needs, Palette.FAINT).spare(),
                this.model.isInstalledVersion(version) ? Kit.text(Lang.get("hotwire.store.installed"), Palette.ON)
                        .spare() : Kit.push(Lang.get(has ? "hotwire.store.switch_this" : "hotwire.store.add_this"),
                                () -> {
                    if (this.model.canAdd()) {
                        this.few.run(shown, () -> this.game.addFromStore(shown.projectId(), version.id(),
                                shown.title()));
                    } else {
                        this.few.run(shown, () -> this.game.saveFromStore(shown.projectId(), version.id(),
                                shown.title()));
                    }
                })).gap(8).cross(Line.Cross.CENTER).press((button, modifiers) -> {
                    this.openedVersion = version.id().equals(this.openedVersion) ? "" : version.id();
                    this.fill();
                });
    }

    @Override
    public void step(float dt) {
        ModrinthClient.Hit shown = this.hit;
        if (shown != null && this.open() && (this.arrived || !this.findState(shown).equals(this.state))) {
            this.arrived = false;
            this.fill();
        }
        super.step(dt);
    }

    @Override
    public boolean onKey(int key, int modifiers) {
        if (key == TextField.ESCAPE) {
            this.dismiss();
        }
        return true;
    }
}
