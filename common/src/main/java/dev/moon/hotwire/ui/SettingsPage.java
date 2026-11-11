package dev.moon.hotwire.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

final class SettingsPage {
    static final String DISCORD = "https://discord.gg/NR5cSrvSDA";

    final Part part;
    final TextField search;
    private final ObjIntConsumer<String> onChoose;
    private final Line body = Kit.column().gap(14);
    private final Scroll scroll = Kit.scroll(this.body);

    private final Map<String, Tabs> bars = new HashMap<>();
    private List<SettingsSection> sections = List.of();

    SettingsPage(ObjIntConsumer<String> onChoose, Consumer<String> openLink, Runnable onEscape) {
        this.onChoose = onChoose;
        this.search = new TextField(Lang.get("hotwire.screen.search"), text -> this.fill(), () -> { }, onEscape);
        this.part = Kit.column(
                Kit.row(Kit.text(Lang.get("hotwire.screen.advanced_short")).big(), Kit.space(),
                        Kit.fit(this.search).width(80).height(20).grow(4).most(160)).gap(8),
                makeDiscordLine(openLink), this.scroll).pad(12, 10).gap(8);
    }

    static Part makeDiscordLine(Consumer<String> openLink) {
        return Kit.row(Kit.dim(Lang.get("hotwire.settings.question")), Kit.row(Kit.text(
                Lang.get("hotwire.settings.discord"), Palette.ACCENT)).press((button, modifiers) ->
                openLink.accept(DISCORD)), Kit.space()).gap(4);
    }

    void set(List<SettingsSection> sections) {
        this.sections = List.copyOf(sections);
        this.fill();
    }

    void scrollToEnd() {
        this.scroll.scrollToEnd();
    }

    private void fill() {
        String sought = this.search.getText().toLowerCase(Locale.ROOT);
        this.body.clear();
        for (SettingsSection section : this.sections) {
            List<SettingOption> options = section.options().stream().filter(option -> holds(option, sought))
                    .toList();
            if (options.isEmpty()) {
                continue;
            }
            Line block = Kit.column(Kit.row(Kit.text(section.title()), Kit.rule().grow(1)).gap(6)).gap(10);
            if (!section.note().isEmpty()) {
                block.with(Kit.text(section.note(), findInk(section.tone())).wrap());
            }
            for (SettingOption option : options) {
                Tabs bar = this.bars.computeIfAbsent(option.id(), id -> this.makeBar(option));
                bar.choose(option.chosen());
                for (int choice = 0; choice < option.choices().size(); choice++) {
                    bar.setActive(choice, section.enabled());
                }
                block.with(Kit.column(Kit.text(option.label(), section.enabled() ? Palette.TEXT : Palette.FAINT), bar,
                        Kit.dim(option.choices().get(option.chosen()).explains()).wrap()).gap(4)
                        .cross(Line.Cross.START));
            }
            this.body.with(block);
        }
        if (!sought.isEmpty() && this.body.isEmpty()) {
            this.body.with(Kit.dim(Lang.get("hotwire.list.no_match", this.search.getText())));
        }
    }

    private Tabs makeBar(SettingOption option) {
        Tabs bar = Kit.tabs(Tabs.Look.BOX, choice -> this.onChoose.accept(option.id(), choice));
        option.choices().forEach(choice -> bar.tab(choice.label()));
        return bar;
    }

    private static boolean holds(SettingOption option, String sought) {
        return option.label().toLowerCase(Locale.ROOT).contains(sought) || option.choices().stream().anyMatch(
                choice -> (choice.label() + " " + choice.explains()).toLowerCase(Locale.ROOT).contains(sought));
    }

    private static int findInk(SettingsSection.Tone tone) {
        return switch (tone) {
            case PLAIN -> Palette.DIM;
            case WARN -> Palette.WARN;
            case GOOD -> Palette.GOOD;
        };
    }
}
