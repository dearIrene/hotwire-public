package dev.moon.hotwire.redefine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moon.hotwire.cache.CacheKey;
import dev.moon.hotwire.classfile.ClassHeader;
import dev.moon.hotwire.mixin_copy.ConfigTrim;
import dev.moon.hotwire.mixin_copy.MixinClassInfo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MixedCacheKeys {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixedCacheKeys.class);
    private final MixedCache owner;

    MixedCacheKeys(MixedCache owner) {
        this.owner = owner;
    }

    CacheKey findHandedAbove(byte[] original, Set<String> targets, Map<String, byte[]> classes) {
        CacheKey.Builder handed = CacheKey.of("handed above 1");
        Set<String> seen = new HashSet<>();
        List<byte[]> next = new ArrayList<>(List.of(original));
        while (!next.isEmpty()) {
            for (String type : ClassHeader.of(next.removeLast()).findAbove()) {
                byte[] bytes = classes.get(type);
                if (bytes != null && !targets.contains(type) && seen.add(type)) {
                    CacheKey shape = findShape(bytes);
                    handed.add(type).add(shape.high()).add(shape.low());
                    next.add(bytes);
                }
            }
        }
        return handed.build();
    }

    static CacheKey findShape(byte[] bytes) {
        CacheKey known = MixedCache.SHAPES.get(bytes);
        if (known != null) {
            return known;
        }
        ClassHeader header = ClassHeader.of(bytes);
        CacheKey.Builder shape = CacheKey.of("shape 2").add(String.valueOf(header.superName()))
                .add(header.access());
        new TreeSet<>(header.interfaces()).forEach(shape::add);
        List<String> members = new ArrayList<>();
        header.fields().forEach(field -> members.add("f " + GeneratedNames.mask(field.name()) + field.desc()
                + " " + field.access()));
        header.methods().forEach(method -> members.add("m " + GeneratedNames.mask(method.name()) + method.desc()
                + " " + method.access()));
        members.sort(null);
        members.forEach(shape::add);
        CacheKey made = shape.build();
        MixedCache.SHAPES.put(bytes, made);
        return made;
    }

    Set<String> findAbove(byte[] original, Map<String, byte[]> classes) {
        Set<String> found = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        List<byte[]> next = new ArrayList<>(List.of(original));
        while (!next.isEmpty()) {
            for (String type : ClassHeader.of(next.removeLast()).findAbove()) {
                if (!seen.add(type) || type.startsWith("java/")) {
                    continue;
                }
                byte[] bytes = classes.get(type);
                if (bytes == null) {
                    bytes = this.owner.copy.findReadable(type);
                }
                found.add(type);
                if (bytes != null) {
                    next.add(bytes);
                }
            }
        }
        return found;
    }

    @Nullable CacheKey findKey(String target, byte[] original, List<MixedCache.Config> configs,
            Map<String, List<MixedCache.AimedMixin>> byAim, CacheKey others, Set<String> above, Set<String> mixedAbove,
            @Nullable StringBuilder trace) {
        CacheKey.Builder key = CacheKey.of(MixedCache.KEYS).add(this.owner.copy.getRecipe()).add(target).add(original)
                .add(others.high()).add(others.low());
        if (trace != null) {
            trace.append(" handed=").append(Long.toHexString(others.high())).append(" above=").append(mixedAbove);
        }
        List<String> types = new ArrayList<>();
        types.add(target);
        types.addAll(mixedAbove);

        TreeSet<MixedCache.AimedMixin> relevant = new TreeSet<>(MixedCache.AIMED_ORDER);
        relevant.addAll(byAim.getOrDefault(target, List.of()));
        above.forEach(type -> relevant.addAll(byAim.getOrDefault(type, List.of())));
        Map<Integer, List<MixedCache.AimedMixin>> byConfig = new TreeMap<>();
        relevant.forEach(one -> byConfig.computeIfAbsent(one.config(), any -> new ArrayList<>()).add(one));
        for (Map.Entry<Integer, List<MixedCache.AimedMixin>> each : byConfig.entrySet()) {
            MixedCache.Config config = configs.get(each.getKey());
            List<String> aiming = new ArrayList<>();
            for (MixedCache.AimedMixin one : each.getValue()) {
                key.add(one.mixin()).add(this.owner.hashOfMixin(one.mixin()));
                for (String type : types) {
                    if (one.aims().contains(type)) {
                        aiming.add(type + " " + one.mixin());
                    }
                }
            }
            if (aiming.isEmpty()) {
                continue;
            }
            key.add(config.settings().high()).add(config.settings().low()).add(this.owner.copy.findDecoration(
                    config.name()));
            if (trace != null) {
                trace.append(" | ").append(config.name()).append(' ').append(Long.toHexString(config.settings().high()))
                        .append(' ').append(aiming.size());
            }
            for (String aimed : aiming) {
                key.add(aimed);
                if (config.plugin()) {
                    int space = aimed.indexOf(' ');
                    Boolean answer = this.owner.answers.ask(config.name(), aimed.substring(0, space).replace('/', '.'),
                            aimed.substring(space + 1).replace('/', '.'));
                    if (answer == null) {
                        if (this.owner.uncached.add(config.name())) {
                            LOGGER.info("[Hotwire] classes {} patches are mixed afresh each time: the game holds no"
                                    + " plugin of it to ask", config.name());
                        }
                        return null;
                    }
                    key.add(answer ? 1 : 0);
                    if (trace != null && !answer) {
                        trace.append(" no:").append(aimed.substring(aimed.indexOf(' ') + 1));
                    }
                }
            }
        }
        return key.build();
    }

    @Nullable Map<String, List<MixedCache.AimedMixin>> indexAims(List<MixedCache.Config> configs) {
        Map<String, List<MixedCache.AimedMixin>> byAim = new HashMap<>();
        for (int at = 0; at < configs.size(); at++) {
            int place = 0;
            for (String mixin : configs.get(at).mixins()) {
                Set<String> aims = this.findAims(mixin);
                if (aims == null) {
                    return null;
                }
                MixedCache.AimedMixin one = new MixedCache.AimedMixin(at, place++, mixin, aims);
                aims.forEach(aim -> byAim.computeIfAbsent(aim, any -> new ArrayList<>()).add(one));
            }
        }
        return byAim;
    }

    MixedCache.Config readConfig(String name, byte[] json, Map<String, byte[]> resources) {
        String refmapName = ConfigTrim.readRefmapOf(json);
        byte[] refmap = refmapName == null ? null : resources.get(refmapName);
        CacheKey bytes = CacheKey.of("config bytes 1").add(name).add(json)
                .add(refmap == null ? new byte[0] : refmap).build();
        return this.owner.parsed.computeIfAbsent(bytes, any -> {
            String text = new String(json, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            root.remove("mixins");
            root.remove("client");
            root.remove("server");
            Set<String> mixins = new TreeSet<>();
            for (String listed : ConfigTrim.readListed(json, this.owner.copy.getSide())) {
                mixins.add(listed.replace('.', '/'));
            }
            this.owner.listed.addAll(mixins);
            ConfigTrim.readUnlisted(json).forEach(unlisted -> mixins.add(unlisted.replace('.', '/')));
            CacheKey settings = CacheKey.of("config 1").add(name).add(root.toString())
                    .add(refmap == null ? new byte[0] : refmap).build();
            return new MixedCache.Config(name, settings, mixins, text.contains(MixedCache.LIVE)
                    || text.contains(MixedCache.PLUGIN));
        });
    }

    @Nullable Set<String> findAims(String mixin) {
        byte[] bytes = this.owner.copy.findReadable(mixin);
        if (bytes == null) {
            return null;
        }
        long hash = this.owner.hashOf(mixin, bytes);
        MixedCache.Aims known = this.owner.aims.get(mixin);
        if (known != null && known.hash() == hash) {
            return known.targets();
        }
        Set<String> read = MixinClassInfo.read(bytes).targets();
        this.owner.aims.put(mixin, new MixedCache.Aims(hash, read));
        return read;
    }
}
