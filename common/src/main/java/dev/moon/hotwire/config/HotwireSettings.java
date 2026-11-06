package dev.moon.hotwire.config;

import dev.moon.hotwire.plan.SwitchPlanner.NestedJars;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.SettingOption;
import dev.moon.hotwire.ui.SettingsSection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
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

    public enum KeyAlert { ON, OFF }

    public enum AutoFill { OFF, ON }

    public enum Developer { OFF, ON }

    public enum UpdateChecks { ON, OFF }

    public enum AntiCrash { OFF, ON }

    public enum DevReload { ON, OFF }

    public enum DevCatch { ON, OFF }

    public enum DevLeaks { ON, OFF }

    public enum ReloadPopups { ALL, PROBLEMS }

    static final String IN_WORLD = "in-world";
    static final String OFF_LASTS = "off-lasts";
    public static final String NESTED_JARS = "nested-jars";
    static final String PAUSE_GAME = "pause-game";
    static final String KEY_ALERT = "key-alert";
    static final String AUTO_FILL = "auto-fill";
    static final String DEVELOPER = "developer";
    static final String UPDATE_CHECKS = "update-checks";
    static final String ANTI_CRASH = "anti-crash";
    static final String DEV_RELOAD = "dev-reload";
    static final String RELOAD_POPUPS = "reload-popups";
    static final String DEV_CATCH = "dev-catch";
    static final String DEV_LEAKS = "dev-leaks";

    private static final Logger LOGGER = LoggerFactory.getLogger(HotwireSettings.class);

    private static final int FORMAT = 2;
    private static final int AUTO_FILL_ON_FROM = 2;

    private static final List<String> IN_WORLD_CHOICES = List.of("hotwire.settings.in_world.auto",
            "hotwire.settings.in_world.off");

    private static final List<InWorld> IN_WORLD_ORDER = List.of(InWorld.AUTO, InWorld.OFF);
    private static final List<OffLasts> OFF_LASTS_ORDER = List.of(OffLasts.SESSION, OffLasts.UNTIL_ON);
    private static final List<String> OFF_LASTS_CHOICES = List.of("hotwire.settings.off_lasts.session",
            "hotwire.settings.off_lasts.until_on");
    private static final List<PauseGame> PAUSE_ORDER = List.of(PauseGame.NO, PauseGame.YES);
    private static final List<String> PAUSE_CHOICES = List.of("hotwire.settings.pause.no",
            "hotwire.settings.pause.yes");
    private static final List<KeyAlert> KEY_ALERT_ORDER = List.of(KeyAlert.OFF, KeyAlert.ON);
    private static final List<String> KEY_ALERT_CHOICES = List.of("hotwire.settings.key_alert.off",
            "hotwire.settings.key_alert.on");
    private static final List<AutoFill> AUTO_FILL_ORDER = List.of(AutoFill.OFF, AutoFill.ON);
    private static final List<String> AUTO_FILL_CHOICES = List.of("hotwire.settings.auto_fill.off",
            "hotwire.settings.auto_fill.on");
    private static final List<Developer> DEVELOPER_ORDER = List.of(Developer.OFF, Developer.ON);
    private static final List<UpdateChecks> UPDATE_CHECKS_ORDER = List.of(UpdateChecks.ON, UpdateChecks.OFF);
    private static final List<String> UPDATE_CHECKS_CHOICES = List.of("hotwire.settings.update_checks.on",
            "hotwire.settings.update_checks.off");
    private static final List<String> DEVELOPER_CHOICES = List.of("hotwire.settings.developer.off",
            "hotwire.settings.developer.on");
    private static final List<NestedJars> NESTED_ORDER = List.of(NestedJars.AUTO, NestedJars.ALL, NestedJars.NONE);
    private static final List<String> NESTED_CHOICES = List.of("hotwire.settings.nested.auto",
            "hotwire.settings.nested.all", "hotwire.settings.nested.none");
    private static final List<AntiCrash> ANTI_CRASH_ORDER = List.of(AntiCrash.OFF, AntiCrash.ON);
    private static final List<String> ANTI_CRASH_CHOICES = List.of("hotwire.settings.anti_crash.off",
            "hotwire.settings.anti_crash.on");
    private static final List<DevReload> DEV_RELOAD_ORDER = List.of(DevReload.OFF, DevReload.ON);
    private static final List<String> DEV_RELOAD_CHOICES = List.of("hotwire.settings.dev_reload.off",
            "hotwire.settings.dev_reload.on");
    private static final List<DevCatch> DEV_CATCH_ORDER = List.of(DevCatch.OFF, DevCatch.ON);
    private static final List<String> DEV_CATCH_CHOICES = List.of("hotwire.settings.dev_catch.off",
            "hotwire.settings.dev_catch.on");
    private static final List<DevLeaks> DEV_LEAKS_ORDER = List.of(DevLeaks.OFF, DevLeaks.ON);
    private static final List<String> DEV_LEAKS_CHOICES = List.of("hotwire.settings.dev_leaks.off",
            "hotwire.settings.dev_leaks.on");
    private static final List<ReloadPopups> RELOAD_POPUPS_ORDER = List.of(ReloadPopups.ALL, ReloadPopups.PROBLEMS);
    private static final List<String> RELOAD_POPUPS_CHOICES = List.of("hotwire.settings.reload_popups.all",
            "hotwire.settings.reload_popups.problems");

    @SuppressWarnings("FieldCanBeFinal")
    private static final class SettingsJson {
        int format = FORMAT;
        @Nullable InWorld inWorld = InWorld.AUTO;
        @Nullable OffLasts offLasts = OffLasts.SESSION;
        @Nullable NestedJars nestedJars = NestedJars.AUTO;
        @Nullable PauseGame pauseGame = PauseGame.NO;
        @Nullable KeyAlert keyAlert = KeyAlert.ON;
        @Nullable AutoFill autoFill = AutoFill.ON;
        @Nullable Developer developer = Developer.OFF;
        @Nullable UpdateChecks updateChecks = UpdateChecks.ON;
        @Nullable AntiCrash antiCrash = AntiCrash.OFF;
        @Nullable DevReload devReload = DevReload.ON;
        @Nullable ReloadPopups reloadPopups = ReloadPopups.ALL;
        @Nullable DevCatch devCatch = DevCatch.ON;
        @Nullable DevLeaks devLeaks = DevLeaks.ON;

        @Nullable List<Double> devWindow;
        @Nullable String promptAcceptedFor;
        boolean keyCardShown;
        @Nullable List<String> favorites = List.of();
    }

    private final JsonFile<SettingsJson> file;
    private volatile InWorld inWorld;
    private volatile OffLasts offLasts;
    private volatile NestedJars nestedJars;
    private volatile PauseGame pauseGame;
    private volatile KeyAlert keyAlert;
    private volatile AutoFill autoFill;
    private volatile Developer developer;
    private volatile UpdateChecks updateChecks;
    private volatile AntiCrash antiCrash;
    private volatile DevReload devReload;
    private volatile ReloadPopups reloadPopups;
    private volatile DevCatch devCatch;
    private volatile DevLeaks devLeaks;
    private volatile double @Nullable [] devWindow;
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
        this.keyAlert = checkKnown(saved.keyAlert, KeyAlert.ON, "keyAlert");
        this.autoFill = saved.format < AUTO_FILL_ON_FROM ? AutoFill.ON
                : checkKnown(saved.autoFill, AutoFill.ON, "autoFill");
        this.developer = checkKnown(saved.developer, Developer.OFF, "developer");
        this.updateChecks = checkKnown(saved.updateChecks, UpdateChecks.ON, "updateChecks");

        this.antiCrash = saved.antiCrash == null ? AntiCrash.OFF : saved.antiCrash;
        this.devReload = saved.devReload == null ? DevReload.ON : saved.devReload;
        this.reloadPopups = saved.reloadPopups == null ? ReloadPopups.ALL : saved.reloadPopups;
        this.devCatch = saved.devCatch == null ? DevCatch.ON : saved.devCatch;
        this.devLeaks = saved.devLeaks == null ? DevLeaks.ON : saved.devLeaks;
        this.devWindow = saved.devWindow == null || saved.devWindow.size() != 5 ? null
                : saved.devWindow.stream().mapToDouble(Double::doubleValue).toArray();
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

    public boolean isKeyAlertOn() {
        return this.keyAlert == KeyAlert.ON;
    }

    public boolean isAutoFillOn() {
        return this.autoFill == AutoFill.ON;
    }

    public boolean isRebuildEverySwitch(boolean inSetup) {
        return inSetup && this.developer == Developer.ON;
    }

    public boolean isAntiCrashOn() {
        return this.antiCrash == AntiCrash.ON;
    }

    public boolean isDevReloadOn(boolean inSetup) {
        return inSetup && this.devReload == DevReload.ON;
    }

    public boolean isDevCatchOn(boolean inSetup) {
        return inSetup && this.devCatch == DevCatch.ON;
    }

    public double @Nullable [] getDevWindow() {
        double[] at = this.devWindow;
        return at == null ? null : at.clone();
    }

    public void setDevWindow(double[] at) {
        this.devWindow = at.clone();
        this.save();
    }

    public boolean isDevLeaksOn(boolean inSetup) {
        return inSetup && this.devLeaks == DevLeaks.ON;
    }

    public boolean isEveryReloadShown() {
        return this.reloadPopups == ReloadPopups.ALL;
    }

    public boolean isUpdateCheckOn() {
        return this.updateChecks == UpdateChecks.ON;
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

    public List<SettingsSection> getSections(DevSetup dev) {
        return List.of(new SettingsSection(Lang.get("hotwire.settings.section.general"), List.of(
                        makeOption(PAUSE_GAME, "hotwire.settings.pause", PAUSE_CHOICES, PAUSE_ORDER, this.pauseGame),
                        makeOption(KEY_ALERT, "hotwire.settings.key_alert", KEY_ALERT_CHOICES, KEY_ALERT_ORDER,
                                this.keyAlert))),
                new SettingsSection(Lang.get("hotwire.settings.section.switching"), List.of(
                        makeOption(OFF_LASTS, "hotwire.settings.off_lasts", OFF_LASTS_CHOICES, OFF_LASTS_ORDER,
                                this.offLasts),
                        makeOption(IN_WORLD, "hotwire.settings.in_world", IN_WORLD_CHOICES, IN_WORLD_ORDER,
                                this.inWorld),
                        makeOption(NESTED_JARS, "hotwire.settings.nested", NESTED_CHOICES, NESTED_ORDER,
                                this.nestedJars))),
                new SettingsSection(Lang.get("hotwire.settings.section.anti_crash"), List.of(
                        makeOption(ANTI_CRASH, "hotwire.settings.anti_crash", ANTI_CRASH_CHOICES, ANTI_CRASH_ORDER,
                                this.antiCrash))),
                new SettingsSection(Lang.get("hotwire.settings.section.updates"), List.of(
                        makeOption(UPDATE_CHECKS, "hotwire.settings.update_checks", UPDATE_CHECKS_CHOICES,
                                UPDATE_CHECKS_ORDER, this.updateChecks),
                        makeOption(AUTO_FILL, "hotwire.settings.auto_fill", AUTO_FILL_CHOICES, AUTO_FILL_ORDER,
                                this.autoFill))),
                this.findDeveloperSection(dev));
    }

    private SettingsSection findDeveloperSection(DevSetup dev) {
        List<SettingOption> options = List.of(
                makeOption(DEV_RELOAD, "hotwire.settings.dev_reload", DEV_RELOAD_CHOICES, DEV_RELOAD_ORDER,
                        this.devReload),
                makeOption(DEV_CATCH, "hotwire.settings.dev_catch", DEV_CATCH_CHOICES, DEV_CATCH_ORDER, this.devCatch),
                makeOption(DEV_LEAKS, "hotwire.settings.dev_leaks", DEV_LEAKS_CHOICES, DEV_LEAKS_ORDER, this.devLeaks),
                makeOption(RELOAD_POPUPS, "hotwire.settings.reload_popups", RELOAD_POPUPS_CHOICES, RELOAD_POPUPS_ORDER,
                        this.reloadPopups),
                makeOption(DEVELOPER, "hotwire.settings.developer", DEVELOPER_CHOICES, DEVELOPER_ORDER,
                        this.developer));
        String title = Lang.get("hotwire.settings.section.developer");
        if (!dev.inSetup()) {
            return new SettingsSection(title, Lang.get("hotwire.settings.developer.not_setup"),
                    SettingsSection.Tone.PLAIN, false, options);
        }
        return switch (dev.jvm()) {
            case FULL -> new SettingsSection(title, Lang.get("hotwire.settings.developer.full"),
                    SettingsSection.Tone.GOOD, true, options);
            case JETBRAINS_WITHOUT_FLAG -> new SettingsSection(title, Lang.get("hotwire.settings.developer.no_flag"),
                    SettingsSection.Tone.WARN, true, options);
            case OTHER -> new SettingsSection(title, Lang.get("hotwire.settings.developer.other_java"),
                    SettingsSection.Tone.WARN, true, options);
        };
    }

    private static <E> SettingOption makeOption(String id, String label, List<String> choices, List<E> order, E now) {
        return new SettingOption(id, Lang.get(label), read(choices), order.indexOf(now));
    }

    private static List<SettingOption.Choice> read(List<String> keys) {
        return keys.stream().map(choice -> new SettingOption.Choice(Lang.get(choice), Lang.get(choice + ".note")))
                .toList();
    }

    public void choose(String option, int choice) {
        InWorld world = IN_WORLD.equals(option) ? pick(IN_WORLD_ORDER, choice, this.inWorld) : this.inWorld;
        OffLasts lasts = OFF_LASTS.equals(option) ? pick(OFF_LASTS_ORDER, choice, this.offLasts) : this.offLasts;
        NestedJars nested = NESTED_JARS.equals(option) ? pick(NESTED_ORDER, choice, this.nestedJars) : this.nestedJars;
        PauseGame pause = PAUSE_GAME.equals(option) ? pick(PAUSE_ORDER, choice, this.pauseGame) : this.pauseGame;
        KeyAlert alert = KEY_ALERT.equals(option) ? pick(KEY_ALERT_ORDER, choice, this.keyAlert) : this.keyAlert;
        AutoFill fill = AUTO_FILL.equals(option) ? pick(AUTO_FILL_ORDER, choice, this.autoFill) : this.autoFill;
        Developer dev = DEVELOPER.equals(option) ? pick(DEVELOPER_ORDER, choice, this.developer) : this.developer;
        UpdateChecks checks = UPDATE_CHECKS.equals(option) ? pick(UPDATE_CHECKS_ORDER, choice, this.updateChecks)
                : this.updateChecks;
        AntiCrash crash = ANTI_CRASH.equals(option) ? pick(ANTI_CRASH_ORDER, choice, this.antiCrash) : this.antiCrash;
        DevReload reload = DEV_RELOAD.equals(option) ? pick(DEV_RELOAD_ORDER, choice, this.devReload) : this.devReload;
        ReloadPopups popups = RELOAD_POPUPS.equals(option) ? pick(RELOAD_POPUPS_ORDER, choice, this.reloadPopups)
                : this.reloadPopups;
        DevCatch caught = DEV_CATCH.equals(option) ? pick(DEV_CATCH_ORDER, choice, this.devCatch) : this.devCatch;
        DevLeaks leaks = DEV_LEAKS.equals(option) ? pick(DEV_LEAKS_ORDER, choice, this.devLeaks) : this.devLeaks;
        if (world == this.inWorld && nested == this.nestedJars && lasts == this.offLasts && pause == this.pauseGame
                && alert == this.keyAlert && fill == this.autoFill && dev == this.developer
                && checks == this.updateChecks && crash == this.antiCrash && reload == this.devReload
                && popups == this.reloadPopups && caught == this.devCatch && leaks == this.devLeaks) {
            return;
        }
        this.devLeaks = leaks;
        this.devCatch = caught;
        this.antiCrash = crash;
        this.devReload = reload;
        this.reloadPopups = popups;
        this.updateChecks = checks;
        this.developer = dev;
        this.autoFill = fill;
        this.pauseGame = pause;
        this.keyAlert = alert;
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
        saved.keyAlert = this.keyAlert;
        saved.autoFill = this.autoFill;
        saved.developer = this.developer;
        saved.updateChecks = this.updateChecks;
        saved.antiCrash = this.antiCrash;
        saved.devReload = this.devReload;
        saved.reloadPopups = this.reloadPopups;
        saved.devCatch = this.devCatch;
        saved.devLeaks = this.devLeaks;
        double[] window = this.devWindow;
        saved.devWindow = window == null ? null : Arrays.stream(window).boxed().toList();
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
