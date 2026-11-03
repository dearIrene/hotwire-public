package dev.moon.hotwire.mixin_copy.copy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class RetainedPluginShape implements IMixinConfigPlugin {
    public static final String PLUGIN = "hotwirePlugin";
    public static final String APPLIED = "hotwireApplied";

    private @Nullable IMixinConfigPlugin plugin;
    private Set<String> applied = Set.of();

    private List<String> unlisted = List.of();

    @Override
    public void onLoad(String mixinPackage) {
        byte[] config = CopyMixinService.findConfigOf(mixinPackage);
        if (config == null) {
            throw new IllegalStateException("no config of the package " + mixinPackage + " was handed to the copy");
        }
        String text = new String(config, StandardCharsets.UTF_8);
        String prefix = mixinPackage + ".";
        Set<String> listed = new HashSet<>();
        readList(text, "mixins").forEach(entry -> listed.add(prefix + entry));
        Set<String> found = new HashSet<>();
        List<String> given = new ArrayList<>();
        for (String name : readList(text, APPLIED)) {
            found.add(name);
            if (!listed.contains(name) && name.startsWith(prefix)) {
                given.add(name.substring(prefix.length()));
            }
        }
        this.applied = found;
        this.unlisted = given;
        Matcher named = Pattern.compile("\"" + PLUGIN + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        if (!named.find()) {
            throw new IllegalStateException("the config of " + mixinPackage + " names no plugin for the copy");
        }
        String name = named.group(1);
        try {
            this.plugin = Class.forName(name, true, CopyMixinService.MODS).asSubclass(IMixinConfigPlugin.class)
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException("the plugin " + name + " could not be made in the copy", e);
        }
        this.plugin.onLoad(mixinPackage);
    }

    private static List<String> readList(String text, String key) {
        Matcher list = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(text);
        List<String> names = new ArrayList<>();
        if (list.find()) {
            Matcher entry = Pattern.compile("\"([^\"]*)\"").matcher(list.group(1));
            while (entry.find()) {
                names.add(entry.group(1));
            }
        }
        return names;
    }

    @Override
    public @Nullable String getRefMapperConfig() {
        return this.plugin == null ? null : this.plugin.getRefMapperConfig();
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.applied.contains(mixinClassName)
                || (this.plugin != null && this.plugin.shouldApplyMixin(targetClassName, mixinClassName));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        if (this.plugin != null) {
            this.plugin.acceptTargets(myTargets, otherTargets);
        }
    }

    @Override
    public @Nullable List<String> getMixins() {
        List<String> own = this.plugin == null ? null : this.plugin.getMixins();
        List<String> all = new ArrayList<>(own == null ? List.of() : own);
        for (String name : this.unlisted) {
            if (!all.contains(name)) {
                all.add(name);
            }
        }
        return all.isEmpty() ? null : all;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (this.plugin != null) {
            this.plugin.preApply(targetClassName, targetClass, mixinClassName, mixinInfo);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        if (this.plugin != null) {
            this.plugin.postApply(targetClassName, targetClass, mixinClassName, mixinInfo);
        }
    }
}
