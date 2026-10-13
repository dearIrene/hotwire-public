package dev.moon.hotwire.fabric.registration;

//? if >=1.21.6 {
import dev.moon.hotwire.registration.CallerOwner;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
//?}

public final class HudElementRecorder {
    //? if >=1.21.6 {
    private record Change(String kind, Object id) {
    }

    private static final Set<Change> KNOWN = new HashSet<>();

    private HudElementRecorder() {
    }

    public static void onAdded(HudElementAddition.Placement placement, @Nullable Identifier anchor,
            Identifier id, HudElement element) {
        if (!isKnown(new Change("add", id))) {
            RecorderSupport.record(id, new HudElementAddition(placement, anchor, id, element));
        }
    }

    public static void onRemoved(Identifier id) {
        if (!isKnown(new Change("remove", id))) {
            RecorderSupport.record(id, new HudElementRemoval(id));
        }
    }

    public static boolean replaceWrapped(Identifier id, Function<HudElement, HudElement> replacer) {
        if (replacer instanceof HudElementReplacement) {
            return false;
        }
        CallerOwner.Owner owner = RecorderSupport.findOwner();
        if (owner == null) {
            return false;
        }
        HudElementReplacement replacement = new HudElementReplacement(id, replacer);
        HudElementRegistryImpl.replaceElement(id, replacement);
        GameRegistrations.getJournal().record(owner.mod(), owner.via(), replacement);
        return true;
    }

    private static boolean isKnown(Change change) {
        synchronized (KNOWN) {
            return !KNOWN.add(change);
        }
    }
    //?}
}
