package dev.moon.hotwire.config;

import org.jspecify.annotations.Nullable;

public final class Flags {
    public static final @Nullable String DUMP = System.getProperty("hotwire.dump");

    public static final @Nullable String DUMP_LOAD = System.getProperty("hotwire.dumpLoad");

    public static final @Nullable String DUMP_SITES = System.getProperty("hotwire.dumpSites");

    public static final boolean SITE_CACHE = Boolean.parseBoolean(System.getProperty("hotwire.siteCache", "true"));

    public static final boolean CLASS_INDEX = Boolean.parseBoolean(System.getProperty("hotwire.classIndex", "true"));

    public static final @Nullable String CACHE_FOLDER = System.getProperty("hotwire.cacheFolder");

    public static final boolean CACHE_VERIFY = Boolean.parseBoolean(System.getProperty("hotwire.cacheVerify"));

    public static final @Nullable String CACHE_VERIFY_DUMP = System.getProperty("hotwire.cacheVerifyDump");

    public static final @Nullable String CACHE_KEY_DUMP = System.getProperty("hotwire.cacheKeyDump");

    public static final boolean CACHE_AUDIT_ALL = Boolean.parseBoolean(System.getProperty("hotwire.cacheAuditAll"));

    public static final boolean FILL_ONE_BY_ONE = Boolean.parseBoolean(System.getProperty("hotwire.fillOneByOne"));

    public static final @Nullable String MIX_WAYS = System.getProperty("hotwire.mixWays");

    public static final boolean NO_FINGERPRINTS = System.getProperty("hotwire.noFingerprints") != null;

    public static final @Nullable String LOST_HOOK = System.getProperty("hotwire.lostHook");

    public static final boolean CHECK_LOOK = Boolean.parseBoolean(System.getProperty("hotwire.checkLook"));

    public static final boolean AHEAD_AT_START = Boolean.parseBoolean(System.getProperty("hotwire.aheadAtStart"));

    public static final boolean NO_LOOK = Boolean.parseBoolean(System.getProperty("hotwire.noLook"));

    public static final boolean CONTENT_LEAVES = Boolean.parseBoolean(System.getProperty("hotwire.contentLeaves"));

    public static final boolean CHECK_SKIPS = System.getProperty("hotwire.checkSkips") != null;

    public static final boolean VERIFY_LIBRARIES = Boolean.parseBoolean(System.getProperty("hotwire.verifyLibraries"));

    public static final boolean VERIFY_CANDIDATES =
            Boolean.parseBoolean(System.getProperty("hotwire.verifyCandidates"));

    public static final boolean SHADERS_AGAIN =
            Boolean.parseBoolean(System.getProperty("hotwire.shadersAgain", "true"));

    public static final String UNLOCK = System.getProperty("hotwire.unlock", "");

    public static final boolean ONECLIENT = System.getProperty("hotwire.oneclient") != null;

    public static final boolean PLAYER_LOG = Boolean.parseBoolean(System.getProperty("hotwire.playerlog"));

    public static final boolean NO_LOCAL_JARS = System.getProperty("hotwire.noLocalJars") != null;

    public static final int COPY_GROUP_CAP = Integer.getInteger("hotwire.copyGroupCap", 12);

    public static final boolean ASK_OVER_CAP = Boolean.parseBoolean(System.getProperty("hotwire.askOverCap"));

    public static final boolean NO_URGENT = Boolean.parseBoolean(System.getProperty("hotwire.noUrgent"));

    private Flags() {
    }

    public static final long HOLD_BUDGET_MILLIS = Long.getLong("hotwire_hold_budget_millis", 30_000);

    public static @Nullable String findGuards() {
        return System.getProperty("hotwire.guards");
    }
}
