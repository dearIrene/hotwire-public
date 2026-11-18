package dev.moon.hotwire.fabric.version;

//? if >=1.21.6 {
import dev.moon.hotwire.engine.CrashGuard;
import dev.moon.hotwire.fabric.screen.GuiCanvas;
import dev.moon.hotwire.registration.CallerOwner;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.RecorderSupport;
import dev.moon.hotwire.registration.Registration;
import dev.moon.hotwire.switching.CrashPolicy;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl;
import net.fabricmc.fabric.impl.client.rendering.hud.HudLayer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class HudElements {
    public enum Placement { FIRST, LAST, BEFORE, AFTER }

    private record Change(String kind, Object id) {
    }

    private final Set<Change> known = new HashSet<>();

    public void onAdded(Placement placement, @Nullable Identifier anchor,
            Identifier id, HudElement element) {
        if (!this.isKnown(new Change("add", id))) {
            RecorderSupport.record(id, new Addition(placement, anchor, id, element));
        }
    }

    public void onRemoved(Identifier id) {
        if (!this.isKnown(new Change("remove", id))) {
            RecorderSupport.record(id, new Removal(id));
        }
    }

    public static boolean replaceWrapped(Identifier id, Function<HudElement, HudElement> replacer) {
        if (replacer instanceof Replacement) {
            return false;
        }
        CallerOwner.Owner owner = RecorderSupport.findOwner();
        if (owner == null) {
            return false;
        }
        Replacement replacement = new Replacement(id, replacer);
        HudElementRegistryImpl.replaceElement(id, replacement);
        GameRegistrations.getJournal().record(owner.mod(), replacement);
        return true;
    }

    private boolean isKnown(Change change) {
        synchronized (this.known) {
            return !this.known.add(change);
        }
    }

    static void addLast(Identifier id, Consumer<GuiCanvas> drawing) {
        try {
            HudElementRegistry.addLast(id, (graphics, delta) -> drawing.accept(new GuiCanvas(graphics,
                    Minecraft.getInstance().font)));
        } catch (IllegalArgumentException e) {
        }
    }

    static void remove(Identifier id) {
        HudElementRegistry.removeElement(id);
    }

    private static final class Addition implements Registration {
        private final Placement placement;
        private final @Nullable Identifier anchor;
        private final Identifier id;
        private final HudElement element;

        Addition(Placement placement, @Nullable Identifier anchor, Identifier id,
                HudElement element) {
            this.placement = placement;
            this.anchor = anchor;
            this.id = id;
            this.element = element;
        }

        @Override
        public String getKind() {
            return "HUD element";
        }

        @Override
        public String describe() {
            return this.id.toString();
        }

        @Override
        public void remove() {
            for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
                Iterator<HudLayer> layers = root.layers().iterator();
                while (layers.hasNext()) {
                    if (layers.next().id().equals(this.id)) {
                        layers.remove();
                    }
                }
            }
        }

        @Override
        public void restore() {
            if (this.isPresent()) {
                return;
            }
            switch (this.placement) {
                case FIRST -> HudElementRegistryImpl.addFirst(this.id, this.element);
                case LAST -> HudElementRegistryImpl.addLast(this.id, this.element);
                case BEFORE -> HudElementRegistryImpl.attachElementBefore(Objects.requireNonNull(this.anchor), this.id,
                        this.element);
                case AFTER -> HudElementRegistryImpl.attachElementAfter(Objects.requireNonNull(this.anchor), this.id,
                        this.element);
            }
        }

        private boolean isPresent() {
            for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
                for (HudLayer layer : root.layers()) {
                    if (layer.id().equals(this.id)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final class Removal implements Registration {
        private final Identifier id;

        Removal(Identifier id) {
            this.id = id;
        }

        @Override
        public String getKind() {
            return "HUD element removal";
        }

        @Override
        public String describe() {
            return this.id.toString();
        }

        @Override
        public void remove() {
            for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
                ListIterator<HudLayer> layers = root.layers().listIterator();
                while (layers.hasNext()) {
                    HudLayer layer = layers.next();
                    if (layer.id().equals(this.id) && layer.isRemoved()) {
                        layers.set(HudLayer.of(this.id, layer::element, false));
                    }
                }
            }
        }

        @Override
        public void restore() {
            for (HudElementRegistryImpl.RootLayer root : HudElementRegistryImpl.ROOT_ELEMENTS.values()) {
                for (HudLayer layer : root.layers()) {
                    if (layer.id().equals(this.id) && !layer.isRemoved()) {
                        HudElementRegistryImpl.removeElement(this.id);
                        return;
                    }
                }
            }
        }
    }

    private static final class Replacement implements Registration, Function<HudElement, HudElement> {
        private final Object id;
        private final Function<HudElement, HudElement> replacer;
        private volatile boolean isActive = true;

        Replacement(Object id, Function<HudElement, HudElement> replacer) {
            this.id = id;
            this.replacer = replacer;
        }

        @Override
        public HudElement apply(HudElement replaced) {
            return this.isActive ? this.replacer.apply(replaced) : replaced;
        }

        @Override
        public String getKind() {
            return "HUD element replacement";
        }

        @Override
        public String describe() {
            return this.id.toString();
        }

        @Override
        public void remove() {
            this.isActive = false;
        }

        @Override
        public void restore() {
            this.isActive = true;
        }
    }

    public static final class Guard implements HudElement {
        private volatile @Nullable HudElement inner;

        private Guard(HudElement inner) {
            this.inner = inner;
        }

        public static HudElement wrap(HudElement element) {
            return element instanceof Guard ? element : new Guard(element);
        }

        @Override
        @SuppressWarnings("checkstyle:IllegalCatch")
        //? if >=26.1 {
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
            HudElement drawn = this.inner;
            try {
                if (drawn != null) {
                    drawn.extractRenderState(graphics, delta);
                }
        //?} else {
        /*public void render(GuiGraphics graphics, DeltaTracker delta) {
            HudElement drawn = this.inner;
            try {
                if (drawn != null) {
                    drawn.render(graphics, delta);
                }
        *///?}
            } catch (RuntimeException | Error e) {
                if (!CrashGuard.handle(e, CrashPolicy.Site.FRAME)) {
                    throw e;
                }
            }
        }
    }
}
//?} else {
/*// Before 1.21.6 Fabric has no HUD element registry: a HUD throw is the frame's (CrashGuard). GameParts holds one
public final class HudElements {
}
*///?}
