package dev.moon.hotwire.ui.list;

import dev.moon.hotwire.ui.Canvas;
import dev.moon.hotwire.ui.Colors;
import dev.moon.hotwire.ui.Element;
import dev.moon.hotwire.ui.Palette;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class ModList extends Element {
    static final int ROW = 42;
    static final int GAP = 4;
    static final int SIDE = 22;
    static final int SPINE = 0x40FFFFFF;
    private static final float PAINT_WAIT = 0.15f;

    private static final int DRAG_DEAD_ZONE = 8;
    private static final float DRAG_ROWS_PER_SECOND = 6;
    private static final Comparator<ModListRow> DRAW_ORDER = Comparator
            .<ModListRow, Boolean>comparing(row -> !row.isGhost()).thenComparingDouble(ModListRow::getY);

    public interface Source {
        List<ModStatus> getShown();

        Map<String, String> getChildOf();

        int countDependants(String id);

        boolean isPicked(String id);
    }

    public interface Badges {
        boolean isDeclaredLibrary(String id);

        String getEnvironmentOf(String id);
    }

    public interface Gestures {
        void toggle(ModStatus mod);

        boolean pick(String id);

        boolean unpick(String id);

        void pickRange(int from, int to);
    }

    private final Source source;
    private final Badges badges;
    private final ModListModel model;
    private final ModCatalog catalog;
    private final Function<String, Canvas.@Nullable Image> icons;
    private final Gestures gestures;
    private int top = 96;
    private int bottomGap = 64;
    private final ListAnimation animation = new ListAnimation();
    private final Map<String, ModListRow> rows = new LinkedHashMap<>();
    private final List<ModListRow> drawn = new ArrayList<>();
    private final List<String> layout = new ArrayList<>();
    private boolean pending;
    private float pendingFor;
    private boolean startAtTop = true;
    private @Nullable String anchor;
    private @Nullable String holding;
    private int painting;
    private int lastClicked = -1;
    private int lastPainted = -1;
    private @Nullable String pointedAt;

    private double dragFromY = Double.NaN;

    public ModList(Source source, Badges badges, ModListModel model, ModCatalog catalog,
            Function<String, Canvas.@Nullable Image> icons, Gestures gestures) {
        this.source = source;
        this.badges = badges;
        this.model = model;
        this.catalog = catalog;
        this.icons = icons;
        this.gestures = gestures;
    }

    public void reach(int top, int bottomGap) {
        this.top = top;
        this.bottomGap = bottomGap;
    }

    int getListTop() {
        return this.top;
    }

    int getListBottom() {
        return this.getHeight() - this.bottomGap;
    }

    int getVisibleRows() {
        return Math.max(1, (this.getListBottom() - this.top) / (ROW + GAP));
    }

    boolean isInList(double x, double y) {
        return x >= 24 && x <= this.getWidth() - 24 && y >= this.top && y <= this.getListBottom();
    }

    ModCatalog getCatalog() {
        return this.catalog;
    }

    ModListModel getModel() {
        return this.model;
    }

    Canvas.@Nullable Image findIcon(String id) {
        Canvas.Image image = this.icons.apply(id);
        String parent = this.source.getChildOf().get(id);
        return image == null && parent != null ? this.icons.apply(parent) : image;
    }

    boolean isPointerIn(int x0, int y0, int x1, int y1) {
        return this.isHovered() && this.getPointerX() >= x0 && this.getPointerX() <= x1 && this.getPointerY() >= y0
                && this.getPointerY() <= y1;
    }

    public @Nullable String findPointedAt() {
        return this.pointedAt;
    }

    int getRowAt(double y) {
        return (int) Math.floor((y - this.top - 6 + this.animation.getScroll() * (ROW + GAP)) / (ROW + GAP));
    }

    float getScrollTarget() {
        return this.animation.getScrollTarget();
    }

    float getScrollShown() {
        return this.animation.getScroll();
    }

    void scrollTo(float target) {
        this.animation.scrollTo(target);
    }

    public void scrollToTop() {
        this.startAtTop = true;
        this.holding = null;
    }

    @Nullable ModListRow findRowFor(String id) {
        return this.rows.get(id);
    }

    boolean isPainting() {
        return this.painting != 0;
    }

    public void setWish(String id, boolean on) {
        ModListRow clicked = this.rows.get(id);
        if (clicked != null) {
            clicked.setWish(on);
        }
    }

    public void clearWishes() {
        this.rows.values().forEach(ModListRow::clearWish);
    }

    public int countLanded(Set<String> ids) {
        int count = 0;
        for (ModListRow row : this.rows.values()) {
            if (ids.contains(row.getId()) && row.getPick() > 0.5f) {
                count++;
            }
        }
        return count;
    }

    public void onChanged() {
        this.anchor = this.startAtTop ? null : this.holding;
        this.pending = true;
        this.pendingFor = 0;
        if (this.painting == 0) {
            this.layOutNow();
        }
    }

    private void layOutNow() {
        if (!this.pending) {
            return;
        }
        this.pending = false;
        boolean replaced = this.startAtTop;
        this.startAtTop = false;
        if (this.isSameList()) {
            for (ModStatus mod : this.source.getShown()) {
                ModListRow row = this.rows.get(mod.id());
                if (row != null) {
                    row.restate(mod, this.badges);
                }
            }
            return;
        }
        List<String> order = new ArrayList<>();
        List<ModListRow> regrouped = new ArrayList<>();
        Map<String, String> childOf = this.source.getChildOf();
        for (ModStatus mod : this.source.getShown()) {
            ModListRow row = this.rows.get(mod.id());
            if (row == null) {
                row = new ModListRow(this, mod);
                this.rows.put(row.getId(), row);
            } else if (childOf.containsKey(row.getId()) != row.isChild()) {
                regrouped.add(row);
            }
            row.restate(mod, this.badges);
            row.regroup(childOf.containsKey(row.getId()), this.source.countDependants(row.getId()));
            order.add(row.getId());
        }
        this.animation.setWindow(this.getVisibleRows());
        if (replaced) {
            this.animation.scrollTo(0);
        }
        this.animation.apply(order, replaced, this.anchor);
        for (ModListRow row : regrouped) {
            row.delayPick(this.animation.getTimeToArrive(row.getId()));
        }
        this.layout.clear();
        this.layout.addAll(order);
    }

    private boolean isSameList() {
        List<ModStatus> shown = this.source.getShown();
        if (shown.size() != this.layout.size()) {
            return false;
        }
        Map<String, String> childOf = this.source.getChildOf();
        for (int i = 0; i < shown.size(); i++) {
            String id = shown.get(i).id();
            ModListRow row = this.rows.get(id);
            if (!id.equals(this.layout.get(i)) || row == null || row.isChild() != childOf.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void step(float dt) {
        if (this.pending) {
            this.pendingFor += dt;
            if (this.painting == 0 || this.pendingFor > PAINT_WAIT) {
                this.layOutNow();
            }
        }
        if (!Double.isNaN(this.dragFromY)) {
            double away = this.getPointerY() - this.dragFromY;
            if (Math.abs(away) > DRAG_DEAD_ZONE) {
                this.animation.scrollTo(this.animation.getScrollTarget()
                        + (float) away / (ROW + GAP) * DRAG_ROWS_PER_SECOND * dt);
            }
        }
        this.animation.setWindow(this.getVisibleRows());
        this.animation.step(dt);
        this.stepRows(dt);
        super.step(dt);
    }

    private void stepRows(float dt) {
        int top = this.getListTop();
        String pointed = null;
        for (ModListRow row : this.rows.values()) {
            row.step(dt, this.source.isPicked(row.getId()));
            ListAnimation.Row place = this.animation.findRow(row.getId());
            if (place != null) {
                row.place(place, this.animation.getScrollTarget());
            }
            int y = this.getRowY(row, top);
            boolean over = this.isHovered() && this.isInList(this.getPointerX(), this.getPointerY())
                    && this.getPointerY() >= y && this.getPointerY() < y + ROW;
            row.easeHover(over, dt);
            if (over) {
                pointed = row.getId();
            }
        }
        this.pointedAt = pointed;
        this.rows.values().removeIf(row -> this.animation.findRow(row.getId()) == null
                && !this.layout.contains(row.getId()));
        this.drawn.clear();
        this.drawn.addAll(this.rows.values());
        this.drawn.sort(DRAW_ORDER);
    }

    private @Nullable ModStatus findRowUnder(double px, double py) {
        List<ModStatus> shown = this.source.getShown();
        int index = this.getRowAt(py);
        return this.isInList(px, py) && index >= 0 && index < shown.size() ? shown.get(index) : null;
    }

    @Override
    protected boolean onClick(double px, double py, int button, int modifiers) {
        if (button == 2 && this.isInList(px, py)) {
            this.dragFromY = py;
            return true;
        }
        ModStatus mod = this.findRowUnder(px, py);
        if (button != 0 || mod == null) {
            return false;
        }
        String id = mod.id();
        if (px >= this.getWidth() - 128 && px <= this.getWidth() - 48) {
            if (!this.catalog.isProtected(id) && !this.catalog.isLocked(id)) {
                this.gestures.toggle(mod);
            }
            return true;
        }
        if (this.catalog.isProtected(id)) {
            return true;
        }
        int index = this.getRowAt(py);
        this.holding = id;
        if ((modifiers & 1) != 0 && this.lastClicked >= 0) {
            this.gestures.pickRange(Math.min(this.lastClicked, index), Math.max(this.lastClicked, index));
            this.painting = 1;
        } else if (this.model.getPicked().contains(id)) {
            this.painting = this.gestures.unpick(id) ? -1 : 0;
        } else {
            this.gestures.pick(id);
            this.painting = 1;
        }
        this.lastClicked = index;
        this.lastPainted = index;
        this.holding = null;
        return true;
    }

    @Override
    protected boolean onDrag(double px, double py, int button) {
        if (!Double.isNaN(this.dragFromY)) {
            return true;
        }
        if (this.painting == 0) {
            return false;
        }
        ModStatus mod = this.findRowUnder(px, py);
        int index = this.getRowAt(py);
        if (mod != null && index != this.lastPainted && !this.catalog.isProtected(mod.id())) {
            if (this.painting > 0) {
                this.gestures.pick(mod.id());
            } else {
                this.gestures.unpick(mod.id());
            }
            this.lastPainted = index;
        }
        return true;
    }

    @Override
    protected boolean onRelease(double px, double py, int button) {
        this.dragFromY = Double.NaN;
        this.painting = 0;
        this.lastPainted = -1;
        this.layOutNow();
        return true;
    }

    @Override
    protected boolean onScroll(double amount) {
        if (!this.isInList(this.getPointerX(), this.getPointerY())) {
            return false;
        }
        this.animation.scrollTo(this.animation.getScrollTarget() - (float) amount * 1.5f);
        return true;
    }

    private int getRowY(ModListRow row, int top) {
        float shown = row.getY() + this.animation.getScrollTarget() - this.animation.getScroll();
        return top + 6 + (int) (shown * (ROW + GAP));
    }

    @Override
    protected void paint(Canvas canvas, float alpha) {
        int top = this.getListTop();
        int bottom = this.getListBottom();
        int width = this.getWidth();
        canvas.fill(24, top, width - 24, bottom, Colors.fade(Palette.PANEL, alpha));
        canvas.fill(24, top, width - 24, top + 1, Colors.fade(Palette.PANEL_EDGE, alpha));
        canvas.fill(24, bottom - 1, width - 24, bottom, Colors.fade(Palette.PANEL_EDGE, alpha));
        if (this.source.getShown().isEmpty()) {
            String filter = this.model.getFilter();
            String nothing = filter.isEmpty() ? "No mods to show here." : "Nothing matches \"" + filter + "\".";
            canvas.drawText(nothing, 44, top + 24, Colors.fade(Palette.DIM, alpha));
            return;
        }
        if (bottom - 1 <= top + 1 || width - 24 <= 24) {
            return;
        }
        canvas.pushClip(24, top + 1, width - 24, bottom - 1);
        for (ModListRow row : this.drawn) {
            int y = this.getRowY(row, top);
            if (row.getAlpha() > 0.01f && y <= bottom && y + ROW >= top) {
                row.paint(canvas, y, alpha);
            }
        }
        canvas.popClip();
        int total = this.source.getShown().size();
        int visible = this.getVisibleRows();
        if (total > visible) {
            int track = bottom - top - 12;
            int thumb = Math.max(20, track * visible / total);
            int offset = (int) (track * (this.animation.getScroll() / total));
            canvas.fill(width - 31, top + 6 + offset, width - 28, top + 6 + offset + thumb, Colors.fade(SPINE, alpha));
        }
    }
}
