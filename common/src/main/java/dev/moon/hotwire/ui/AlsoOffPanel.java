package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class AlsoOffPanel extends Sheet {
    private static final int MOST_REASONS = 4;
    private static final int LIST_TALL = 110;

    private static final float GIVE_UP = 3;

    private final Consumer<List<String>> onConfirm;
    private final Runnable onCancel;
    private final Progress progress;
    private final Function<String, Canvas.@Nullable Image> icons;
    private boolean confirmed;
    private float waited;

    public AlsoOffPanel(Consumer<List<String>> onConfirm, Runnable onCancel, Progress progress,
            Function<String, Canvas.@Nullable Image> icons) {
        super(420, onCancel);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.progress = progress;
        this.icons = icons;
    }

    void open(List<String> ids, List<Progress.Entry> queue) {
        String asked = Lang.get("hotwire.progress.why.asked");
        List<String> picked = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Line others = Kit.column().gap(4);
        for (Progress.Entry entry : queue) {
            if (entry.why().equals(asked)) {
                picked.add(entry.name());
                continue;
            }
            counts.merge(entry.why(), 1, Integer::sum);
            others.with(Kit.row(Kit.picture(() -> this.icons.apply(entry.id()), entry.name(), 12),
                    Kit.text(entry.name()).grow(1), Kit.text(entry.why(), Palette.FAINT).spare()).gap(6)
                    .cross(Line.Cross.CENTER));
        }
        this.title.set(picked.size() == 1 ? Lang.get("hotwire.also_off.title", picked.getFirst())
                : Lang.get("hotwire.also_off.title_many", picked.size()));
        this.body.clear().with(Kit.text(Lang.get("hotwire.also_off.line")).wrap());

        List<Map.Entry<String, Integer>> reasons = new ArrayList<>(counts.entrySet());
        reasons.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        reasons.stream().limit(MOST_REASONS).forEach(reason -> this.body.with(Kit.row(Kit.text(
                String.valueOf(reason.getValue()), Palette.ACCENT).width(16), Kit.dim(reason.getKey()).wrap()
                        .grow(1))));
        if (reasons.size() > MOST_REASONS) {
            this.body.with(Kit.text(Lang.get("hotwire.also_off.more_reasons", reasons.size() - MOST_REASONS),
                    Palette.FAINT));
        }
        this.body.with(Kit.column(Kit.scroll(others).capAt(LIST_TALL)).pad(8, 6).face(Palette.HAIRLINE));
        this.confirmed = false;
        this.waited = 0;
        List<String> wanted = List.copyOf(ids);
        this.buttons.clear().with(Kit.space(), this.setLead(Kit.lead(Lang.get("hotwire.also_off.confirm",
                queue.size()), () -> {
                    if (!this.confirmed) {
                        this.confirmed = true;
                        this.getLead().active(false);
                        this.onConfirm.accept(wanted);
                    }
                })), Kit.push(Lang.get("hotwire.button.cancel"), this.onCancel));
        this.show();
    }

    @Override
    public void step(float dt) {
        if (this.confirmed && this.open()) {
            this.waited += dt;
            if (this.progress.isBusy() || this.waited > GIVE_UP) {
                this.dismiss();
            }
        }
        super.step(dt);
    }
}
