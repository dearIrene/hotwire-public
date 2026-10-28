package dev.moon.hotwire.mixin_copy;

import java.util.List;
import java.util.Set;
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

    private final Set<String> launched;

    public ExtensionFolder(List<String> launched) {
        this.launched = Set.copyOf(launched);
    }

    public boolean isFolded(String internal) {
        Matcher found = RELOCATED.matcher(internal);
        return found.matches() && !this.isLaunched(found.group(1) + "mixinextras/");
    }

    private boolean isLaunched(String prefix) {
        return prefix.equals(GAME) || this.launched.contains(prefix);
    }

    public byte[] fold(byte[] bytes) {
        boolean[] names = {false};
        Remapper toGame = new Remapper() {
            @Override
            public String map(String internalName) {
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
