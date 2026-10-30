package dev.moon.hotwire.config;

import dev.moon.hotwire.plan.SwitchPlanner.NestedJars;
import dev.moon.hotwire.ui.AdvancedOption;
import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HotwireSettings {
    public enum InWorld { AUTO, OFF }

    public enum OffLasts { SESSION, UNTIL_ON }

    public enum PauseGame { NO, YES }

    static final String IN_WORLD = "in-world";
    static final String OFF_LASTS = "off-lasts";
    public static final String NESTED_JARS = "nested-jars";
    static final String PAUSE_GAME = "pause-game";

    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireSettings.class);
    private static final int FORMAT = 1;

    private static final List<String> IN_WORLD_CHOICES = List.of("hotwire.settings.in_world.auto",
            "hotwire.settings.in_world.off");

    private static final List<InWorld> IN_WORLD_ORDER = List.of(InWorld.AUTO, InWorld.OFF);
    private static final List<OffLasts> OFF_LASTS_ORDER = List.of(OffLasts.SESSION, OffLasts.UNTIL_ON);
    private static final List<String> OFF_LASTS_CHOICES = List.of("hotwire.settings.off_lasts.session",
            "hotwire.settings.off_lasts.until_on");
    private static final List<PauseGame> PAUSE_ORDER = List.of(PauseGame.NO, PauseGame.YES);
    private static final List<String> PAUSE_CHOICES = List.of("hotwire.settings.pause.no",
            "hotwire.settings.pause.yes");
    private static final List<NestedJars> NESTED_ORDER = List.of(NestedJars.AUTO, NestedJars.ALL, NestedJars.NONE);
    private static final List<String> NESTED_CHOICES = List.of("hotwire.settings.nested.auto",
            "hotwire.settings.nested.all", "hotwire.settings.nested.none");

    @SuppressWarnings("FieldCanBeFinal")
    private static final class SettingsJson {
        int format = FORMAT;
        @Nullable InWorld inWorld = InWorld.AUTO;
        @Nullable OffLasts offLasts = OffLasts.SESSION;
        @Nullable NestedJars nestedJars = NestedJars.AUTO;
        @Nullable PauseGame pauseGame = PauseGame.NO;
        @Nullable String promptAcceptedFor;
        boolean keyCardShown;
        @Nullable List<String> favorites = List.of();
    }

    private final JsonFile<SettingsJson> file;
    private volatile InWorld inWorld;
    private volatile OffLasts offLasts;
    private volatile NestedJars nestedJars;
    private volatile PauseGame pauseGame;
    private volatile @Nullable String promptAcceptedFor;
    private volatile boolean keyCardShown;
    private final Set<String> favorites = ConcurrentHashMap.newKeySet();

    public HotwireSettings(Path path) {
        this.file = new JsonFile<>(path, SettingsJson.class, SettingsJson::new);
        SettingsJson saved = this.file.load();
        if (saved.format > FORMAT) {
            LOGGER.warn("[Hotwire] settings.json is from a newer Hotwire (format {}); what this one knows is read",
                    saved.format);
        }
        this.inWorld = checkKnown(saved.inWorld, InWorld.AUTO, "inWorld");
        this.offLasts = checkKnown(saved.offLasts, OffLasts.SESSION, "offLasts");
        this.nestedJars = checkKnown(saved.nestedJars, NestedJars.AUTO, "nestedJars");
        this.pauseGame = checkKnown(saved.pauseGame, PauseGame.NO, "pauseGame");
        this.promptAcceptedFor = saved.promptAcceptedFor;
        this.keyCardShown = saved.keyCardShown;
        if (saved.favorites != null) {
            this.favorites.addAll(saved.favorites);
        }
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

    public OffLasts getOffLasts() {
        return this.offLasts;
    }

    public NestedJars getNestedJars() {
        return this.nestedJars;
    }

    public boolean isPausing() {
        return this.pauseGame == PauseGame.YES;
    }

    public boolean isPromptAccepted(String major) {
        return major.equals(this.promptAcceptedFor);
    }

    public boolean isFavorite(String id) {
        return this.favorites.contains(id);
    }

    public void setFavorite(String id, boolean favorite) {
        if (favorite ? this.favorites.add(id) : this.favorites.remove(id)) {
            this.save();
        }
    }

    public boolean isKeyCardShown() {
        return this.keyCardShown;
    }

    public void markKeyCardShown() {
        this.keyCardShown = true;
        this.save();
    }

    public void acceptPrompt(String major) {
        this.promptAcceptedFor = major;
        this.save();
    }

    public List<AdvancedOption> getOptions() {
        return List.of(new AdvancedOption(OFF_LASTS, Lang.get("hotwire.settings.off_lasts"),
                read(OFF_LASTS_CHOICES), OFF_LASTS_ORDER.indexOf(this.offLasts)),
                new AdvancedOption(IN_WORLD, Lang.get("hotwire.settings.in_world"), read(IN_WORLD_CHOICES),
                        IN_WORLD_ORDER.indexOf(this.inWorld)),
                new AdvancedOption(NESTED_JARS, Lang.get("hotwire.settings.nested"), read(NESTED_CHOICES),
                        NESTED_ORDER.indexOf(this.nestedJars)),
                new AdvancedOption(PAUSE_GAME, Lang.get("hotwire.settings.pause"), read(PAUSE_CHOICES),
                        PAUSE_ORDER.indexOf(this.pauseGame)));
    }

    private static List<AdvancedOption.Choice> read(List<String> keys) {
        return keys.stream().map(choice -> new AdvancedOption.Choice(Lang.get(choice), Lang.get(choice + ".note")))
                .toList();
    }

    public void choose(String option, int choice) {
        InWorld world = IN_WORLD.equals(option) ? pick(IN_WORLD_ORDER, choice, this.inWorld) : this.inWorld;
        OffLasts lasts = OFF_LASTS.equals(option) ? pick(OFF_LASTS_ORDER, choice, this.offLasts) : this.offLasts;
        NestedJars nested = NESTED_JARS.equals(option) ? pick(NESTED_ORDER, choice, this.nestedJars) : this.nestedJars;
        PauseGame pause = PAUSE_GAME.equals(option) ? pick(PAUSE_ORDER, choice, this.pauseGame) : this.pauseGame;
        if (world == this.inWorld && nested == this.nestedJars && lasts == this.offLasts && pause == this.pauseGame) {
            return;
        }
        this.pauseGame = pause;
        this.inWorld = world;
        this.offLasts = lasts;
        this.nestedJars = nested;
        this.save();
    }

    private void save() {
        SettingsJson saved = new SettingsJson();
        saved.inWorld = this.inWorld;
        saved.offLasts = this.offLasts;
        saved.nestedJars = this.nestedJars;
        saved.pauseGame = this.pauseGame;
        saved.promptAcceptedFor = this.promptAcceptedFor;
        saved.keyCardShown = this.keyCardShown;
        saved.favorites = this.favorites.stream().sorted().toList();
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
