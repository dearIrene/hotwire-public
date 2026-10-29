package dev.moon.hotwire.ui;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.StoreModel;
import java.util.ArrayList;
import java.util.List;

final class DownloadMenu {
    static final int WIDTH = 150;

    private final Dropdown menu;
    private final StoreModel model;
    private final StoreDetail.Adds adds;
    private List<Runnable> choices = List.of();
    private String shownFor = "";

    DownloadMenu(Element menus, StoreModel model, StoreDetail.Adds adds) {
        this.model = model;
        this.adds = adds;
        this.menu = new Dropdown(menus, "", index -> this.choices.get(index).run(), index -> { });
    }

    void toggle(ModrinthClient.Hit hit, String versionId, int left, int y, boolean upward) {
        String id = hit.projectId();
        if (id.equals(this.shownFor) && this.menu.isShown()) {
            this.menu.close();
            return;
        }
        StoreModel.Card card = this.model.getCardsByProject().get(id);
        boolean installed = this.model.isInstalledProject(id) || (card != null && card.installed());
        List<Dropdown.Item> items = new ArrayList<>();
        List<Runnable> picked = new ArrayList<>();
        if (this.model.canAdd() && !installed && !this.model.isQueuedProject(id)) {
            items.add(Dropdown.Item.of(Lang.get("hotwire.store.download.next_start")));
            picked.add(() -> this.adds.queueForNextStart(id, versionId, hit.title()));
        }
        items.add(Dropdown.Item.of(Lang.get("hotwire.store.download.to_folder")));
        picked.add(() -> this.adds.saveToDownloads(id, versionId, hit.title()));
        this.choices = picked;
        this.shownFor = id;
        this.menu.setItems(items);

        int top = upward ? y - items.size() * Dropdown.ITEM : y;
        this.menu.setBounds(left, top - 2 - Dropdown.HEIGHT, WIDTH, Dropdown.HEIGHT);
        this.menu.openMenu();
    }

    void close() {
        this.menu.close();
    }
}
