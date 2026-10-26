package dev.moon.hotwire.config;

import dev.moon.hotwire.plan.SwitchPlanner.NestedJars;
import dev.moon.hotwire.ui.AdvancedOption;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireSettings {
    public enum InWorld { AUTO, OFF }

    static final String IN_WORLD = "in-world";
    public static final String NESTED_JARS = "nested-jars";

    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireSettings.class);
    private static final int FORMAT = 1;

    private static final List<String> IN_WORLD_CHOICES = List.of("hotwire.settings.in_world.auto",
            "hotwire.settings.in_world.off");

    private static final List<InWorld> IN_WORLD_ORDER = List.of(InWorld.AUTO, InWorld.OFF);
    private static final List<NestedJars> NESTED_ORDER = List.of(NestedJars.AUTO, NestedJars.ALL, NestedJars.NONE);
    private static final List<String> NESTED_CHOICES = List.of("hotwire.settings.nested.auto",
            "hotwire.settings.nested.all", "hotwire.settings.nested.none");

    @SuppressWarnings("FieldCanBeFinal")
    private static final class SettingsJson {
        int format = FORMAT;
        @Nullable InWorld inWorld = InWorld.AUTO;
        @Nullable NestedJars nestedJars = NestedJars.AUTO;
        @Nullable String promptAcceptedFor;
    }

    private final JsonFile<SettingsJson> file;
    private volatile InWorld inWorld;
    private volatile NestedJars nestedJars;
    private volatile @Nullable String promptAcceptedFor;

    public HotwireSettings(Path path) {
        this.file = new JsonFile<>(path, SettingsJson.class, SettingsJson::new);
        SettingsJson saved = this.file.load();
        if (saved.format > FORMAT) {
            LOGGER.warn("[Hotwire] settings.json is from a newer Hotwire (format {}); what this one knows is read",
                    saved.format);
        }
        this.inWorld = checkKnown(saved.inWorld, InWorld.AUTO, "inWorld");
        this.nestedJars = checkKnown(saved.nestedJars, NestedJars.AUTO, "nestedJars");
        this.promptAcceptedFor = saved.promptAcceptedFor;
    }

    private static <E extends Enum<E>> E checkKnown(@Nullable E value, E fallback, String name) {
        if (value == null) {
            LOGGER.warn("[Hotwire] settings.json has no known {}; it is {}", name, fallback);
            return fallback;
        }
        return value;
    }

    public InWorld getInWorld() {
        return this.inWorld;
    }

    public NestedJars getNestedJars() {
        return this.nestedJars;
    }

    public boolean isPromptAccepted(String major) {
        return major.equals(this.promptAcceptedFor);
    }

    public void acceptPrompt(String major) {
        this.promptAcceptedFor = major;
        this.save();
    }

    public List<AdvancedOption> getOptions() {
        return List.of(new AdvancedOption(IN_WORLD, Lang.get("hotwire.settings.in_world"), read(IN_WORLD_CHOICES),
                IN_WORLD_ORDER.indexOf(this.inWorld)),
                new AdvancedOption(NESTED_JARS, Lang.get("hotwire.settings.nested"), read(NESTED_CHOICES),
                        NESTED_ORDER.indexOf(this.nestedJars)));
    }

    private static List<AdvancedOption.Choice> read(List<String> keys) {
        return keys.stream().map(choice -> new AdvancedOption.Choice(Lang.get(choice), Lang.get(choice + ".note")))
                .toList();
    }

    public void choose(String option, int choice) {
        InWorld world = IN_WORLD.equals(option) ? pick(IN_WORLD_ORDER, choice, this.inWorld) : this.inWorld;
        NestedJars nested = NESTED_JARS.equals(option) ? pick(NESTED_ORDER, choice, this.nestedJars) : this.nestedJars;
        if (world == this.inWorld && nested == this.nestedJars) {
            return;
        }
        this.inWorld = world;
        this.nestedJars = nested;
        this.save();
    }

    private void save() {
        SettingsJson saved = new SettingsJson();
        saved.inWorld = this.inWorld;
        saved.nestedJars = this.nestedJars;
        saved.promptAcceptedFor = this.promptAcceptedFor;
        try {
            this.file.save(saved);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] settings could not be saved; they hold until the game closes", e);
        }
    }

    private static <E> E pick(List<E> order, int choice, E current) {
        return choice >= 0 && choice < order.size() ? order.get(choice) : current;
    }
}
