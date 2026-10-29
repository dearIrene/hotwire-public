package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class CategoryBar extends Element {
    record Group(String name, List<String> tags) {
        static Group of(String tag) {
            return new Group("", List.of(tag));
        }
    }

    private static final int MENU_WIDTH = 150;

    private final ChoiceBar bar;
    private final Dropdown menu;
    private final Consumer<String> onPick;
    private List<Group> groups = List.of();
    private String chosen = "";

    private int open = -1;

    CategoryBar(Element menus, Consumer<String> onPick) {
        this.onPick = onPick;
        this.bar = this.add(new ChoiceBar(List.of(), 0, this::onChoice));
        this.menu = new Dropdown(menus, "", this::onMenuPick, index -> { });
    }

    void setGroups(List<Group> groups, String chosen) {
        this.groups = List.copyOf(groups);
        this.chosen = chosen;
        this.label();
    }

    @Override
    protected void layOut() {
        this.bar.setBounds(this.getX(), this.getY(), this.getWidth(), ChoiceBar.HEIGHT);
    }

    private void label() {
        List<ChoiceBar.Choice> choices = new ArrayList<>();
        int lit = 0;
        for (int at = 0; at < this.groups.size(); at++) {
            Group group = this.groups.get(at);
            boolean holds = group.tags().contains(this.chosen);
            lit = holds ? at : lit;
            choices.add(group.tags().size() == 1 ? ChoiceBar.Choice.of(nameTag(group.tags().getFirst()))
                    : ChoiceBar.Choice.ofMenu(holds ? nameTag(this.chosen)
                            : Lang.get("hotwire.store.section." + group.name())));
        }
        this.bar.setChoices(choices);
        this.bar.setChosen(lit);
    }

    private static String nameTag(String tag) {
        return Lang.get(tag.isEmpty() ? "hotwire.store.category.all" : "hotwire.store.category." + tag);
    }

    private void onChoice(int index) {
        Group group = this.groups.get(index);
        if (group.tags().size() == 1) {
            this.pick(group.tags().getFirst());
            return;
        }
        this.label();
        int left = this.bar.findLeft(index);
        this.menu.setBounds(left, this.getY(), Math.max(MENU_WIDTH, this.bar.findRight(index) - left),
                ChoiceBar.HEIGHT);
        this.menu.setItems(group.tags().stream().map(tag -> Dropdown.Item.of(nameTag(tag))).toList());

        boolean same = index == this.open && this.menu.isShown();
        this.open = index;
        if (same) {
            this.menu.close();
        } else {
            this.menu.openMenu();
        }
    }

    private void onMenuPick(int index) {
        if (this.open >= 0 && this.open < this.groups.size()) {
            this.pick(this.groups.get(this.open).tags().get(index));
        }
    }

    private void pick(String tag) {
        this.chosen = tag;
        this.label();
        this.onPick.accept(tag);
    }
}
