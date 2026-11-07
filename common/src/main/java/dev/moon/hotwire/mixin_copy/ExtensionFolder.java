package dev.moon.hotwire.mixin_copy;

import dev.moon.hotwire.redefine.ClassBytes;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class ExtensionFolder {
    public static final ExtensionFolder NONE = new ExtensionFolder(List.of());
    private static final String GAME = "com/llamalad7/mixinextras/";
    private static final Pattern RELOCATED = Pattern.compile("^(.+/)mixinextras/(.*)$");

    private static final String MARK = "mixinextras/";
    private static final byte[] MARK_BYTES = MARK.getBytes(StandardCharsets.UTF_8);

    private final Set<String> launched;

    @SuppressWarnings("ArrayAsKeyOfSetOrMap")
    private final Map<byte[], Optional<byte[]>> folded = Collections.synchronizedMap(new WeakHashMap<>());

    public ExtensionFolder(List<String> launched) {
        this.launched = Set.copyOf(launched);
    }

    public boolean isFolded(String internal) {
        if (!internal.contains(MARK)) {
            return false;
        }
        Matcher found = RELOCATED.matcher(internal);
        return found.matches() && !this.isLaunched(found.group(1) + "mixinextras/");
    }

    private boolean isLaunched(String prefix) {
        return prefix.equals(GAME) || this.launched.contains(prefix);
    }

    @SuppressWarnings("ReferenceEquality")
    public byte[] fold(byte[] bytes) {
        Optional<byte[]> known = this.folded.get(bytes);
        if (known == null) {
            byte[] made = this.foldAfresh(bytes);
            known = made == bytes ? Optional.empty() : Optional.of(made);
            this.folded.put(bytes, known);
        }
        return known.orElse(bytes);
    }

    private byte[] foldAfresh(byte[] bytes) {
        if (!ClassBytes.mentions(bytes, MARK_BYTES)) {
            return bytes;
        }
        boolean[] names = {false};
        Remapper toGame = new Remapper() {
            @Override
            public String map(String internalName) {
                if (!internalName.contains(MARK)) {
                    return internalName;
                }
                Matcher found = RELOCATED.matcher(internalName);
                if (!found.matches() || ExtensionFolder.this.isLaunched(found.group(1) + "mixinextras/")) {
                    return internalName;
                }
                names[0] = true;
                return GAME + found.group(2);
            }
        };
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, toGame), 0);
        return names[0] ? writer.toByteArray() : bytes;
    }
}
