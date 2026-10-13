package dev.moon.hotwire.config;

import dev.moon.hotwire.plan.SwitchPlanner.NestedJars;
import dev.moon.hotwire.ui.AdvancedOption;
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
    private static final List<AdvancedOption.Choice> IN_WORLD_CHOICES = List.of(
            new AdvancedOption.Choice("Auto", "Hotwire decides for each mod, and says when one must wait."),
            new AdvancedOption.Choice("Off", "Never inside a world; leave it first."));

    private static final List<InWorld> IN_WORLD_ORDER = List.of(InWorld.AUTO, InWorld.OFF);
    private static final List<NestedJars> NESTED_ORDER = List.of(NestedJars.AUTO, NestedJars.ALL, NestedJars.NONE);
    private static final List<AdvancedOption.Choice> NESTED_CHOICES = List.of(
            new AdvancedOption.Choice("Auto", "A mod's own parts go off and on with it."),
            new AdvancedOption.Choice("All", "Every jar a mod carries goes with it."),
            new AdvancedOption.Choice("None", "Jars a mod carries stay as they are."));

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
        return List.of(new AdvancedOption(IN_WORLD, "In-world switching", IN_WORLD_CHOICES,
                IN_WORLD_ORDER.indexOf(this.inWorld)),
                new AdvancedOption(NESTED_JARS, "Nested jars", NESTED_CHOICES,
                        NESTED_ORDER.indexOf(this.nestedJars)));
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
