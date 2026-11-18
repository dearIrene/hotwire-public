package dev.moon.hotwire.ui.pages;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.widgets.Kit;
import dev.moon.hotwire.ui.widgets.Palette;
import dev.moon.hotwire.ui.widgets.Sheet;
import org.jspecify.annotations.Nullable;

public final class FewDownloadsSheet extends Sheet {
    static final long FEW = 10_000;

    private @Nullable Runnable then;

    @SuppressWarnings("this-escape")
    FewDownloadsSheet() {
        super(360, null);
        this.title.set(Lang.get("hotwire.store.few.title"));
        Runnable cancel = () -> {
            this.then = null;
            this.dismiss();
        };
        this.buttons.with(Kit.space(), Kit.push(Lang.get("hotwire.store.few.cancel"), cancel),
                this.setLead(Kit.lead(Lang.get("hotwire.store.few.go"), this::runWaiting)));
    }

    void run(ModrinthClient.Hit hit, Runnable download) {
        if (hit.downloads() >= FEW) {
            download.run();
            return;
        }
        this.then = download;
        this.body.clear().with(Kit.text(Lang.get("hotwire.store.few.line", hit.title(),
                StorePage.describeDownloads(hit.downloads())), Palette.BAD).wrap(),
                Kit.dim(Lang.get("hotwire.store.few.sure")).wrap());
        this.show();
    }

    private void runWaiting() {
        Runnable download = this.then;
        this.then = null;
        this.dismiss();
        if (download != null) {
            download.run();
        }
    }
}
