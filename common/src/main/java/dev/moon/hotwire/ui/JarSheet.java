package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

abstract class JarSheet<P> extends Sheet {
    private final Runnable onChange;
    private final Runnable onCancel;
    private @Nullable Path jar;
    private CompletableFuture<P> reading = new CompletableFuture<>();
    private @Nullable P preview;

    JarSheet(int width, Runnable onChange, Runnable onCancel) {
        super(width, onCancel);
        this.onChange = onChange;
        this.onCancel = onCancel;
    }

    @Override
    public boolean isReading() {
        return this.preview == null;
    }

    void read(Path jar, CompletableFuture<P> dryRun) {
        this.jar = jar;
        this.reading = dryRun;
        this.preview = null;
        this.setRefused(false);
        this.title.set(Lang.get("hotwire.add.lead.reading") + this.getFileName()).ink(Palette.TEXT);
        this.setSub("");
        this.body.clear().with(Kit.dim(Lang.get("hotwire.panel.just_a_moment")));
        this.setButtons(null);
        this.show();
    }

    @Nullable P getPreview() {
        return this.preview;
    }

    protected String getFileName() {
        Path picked = this.jar;
        Path name = picked == null ? null : picked.getFileName();
        return name == null ? "" : name.toString();
    }

    protected void setButtons(@Nullable Push lead) {
        this.buttons.clear().with(Kit.push(Lang.get("hotwire.button.pick_another"), this.onChange)
                .active(this.preview != null), Kit.space());
        this.setLead(lead);
        if (lead != null) {
            this.buttons.with(lead);
        }
        this.buttons.with(Kit.push(Lang.get("hotwire.button.cancel"), this.onCancel));
    }

    protected abstract P makeFailed(Throwable cause);

    protected abstract void fill(P preview, Path jar);

    @Override
    public void step(float dt) {
        Path picked = this.jar;
        if (this.preview == null && picked != null && this.reading.isDone()) {
            P found;
            try {
                found = this.reading.join();
            } catch (RuntimeException e) {
                found = this.makeFailed(e.getCause() == null ? e : e.getCause());
            }
            this.preview = found;
            this.fill(found, picked);
        }
        super.step(dt);
    }
}
