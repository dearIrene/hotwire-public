package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class ReopenPanel extends Sheet {
    public record Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why, boolean leaves,
            boolean nextStart, boolean backup) {
        public Offer {
            ids = List.copyOf(ids);
        }

        public Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why, boolean leaves) {
            this(off, ids, jar, who, why, leaves, false, false);
        }

        public Offer(boolean off, List<String> ids, @Nullable Path jar, String who, String why) {
            this(off, ids, jar, who, why, false, false, false);
        }

        public Offer(boolean off, List<String> ids, String who, String why) {
            this(off, ids, null, who, why, false, false, false);
        }

        public static Offer ofNextStart(List<String> ids, String who, String why) {
            return new Offer(true, ids, null, who, why, false, true, false);
        }

        Offer withBackup(boolean backup) {
            return new Offer(this.off, this.ids, this.jar, this.who, this.why, this.leaves, this.nextStart, backup);
        }

        boolean isReopening() {
            return !this.leaves && !this.nextStart;
        }

        boolean isReopenOnly() {
            return !this.leaves && !this.nextStart && this.jar == null && this.ids.isEmpty();
        }
    }

    private static boolean backupWanted = true;

    private final Consumer<Offer> onConfirm;
    private final Runnable onCancel;

    public ReopenPanel(Consumer<Offer> onConfirm, Runnable onCancel) {
        super(440, onCancel);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public void open(Offer offer) {
        this.fill(offer);
        this.show();
    }

    private void fill(Offer offer) {
        this.title.set(findTitle(offer));
        String safe = offer.leaves() ? "hotwire.reopen.safe.server" : "hotwire.reopen.safe.world";
        this.body.clear().with(Kit.text(offer.who(), Palette.ACCENT).wrap(), Kit.dim(offer.why()).wrap(),
                new Part().height(4), Kit.text(findPlan(offer)).wrap(),
                Kit.text(Lang.get(offer.nextStart() ? "hotwire.reopen.safe.next_start" : safe),
                        offer.nextStart() ? Palette.WARN : Palette.ON).wrap(),
                Kit.dim(Lang.get("hotwire.reopen.backup_line")).wrap().show(offer.isReopening() && backupWanted));
        this.buttons.clear().with(Kit.push(Lang.get(backupWanted ? "hotwire.reopen.backup_on"
                : "hotwire.reopen.backup_off"), () -> {
                    backupWanted = !backupWanted;
                    this.fill(offer);
                }).show(offer.isReopening()), Kit.space(),
                this.setLead(Kit.lead(Lang.get(findConfirmKey(offer)),
                        () -> this.onConfirm.accept(offer.withBackup(offer.isReopening() && backupWanted)))),
                Kit.push(Lang.get(offer.isReopenOnly() ? "hotwire.reopen.later" : "hotwire.button.cancel"),
                        this.onCancel));
    }

    private static String findConfirmKey(Offer offer) {
        if (offer.nextStart()) {
            return "hotwire.reopen.off_next_start";
        }
        if (offer.leaves()) {
            return "hotwire.reopen.leave_and_switch";
        }
        if (offer.jar() != null) {
            return "hotwire.add.button.save_and_add";
        }
        return offer.isReopenOnly() ? "hotwire.reopen.rejoin_now" : "hotwire.reopen.save_and_switch";
    }

    static String findTitle(Offer offer) {
        if (offer.nextStart()) {
            return Lang.get("hotwire.reopen.title.next_start");
        }
        if (offer.leaves()) {
            return Lang.get("hotwire.reopen.title.leave");
        }
        return Lang.get(offer.isReopenOnly() ? "hotwire.reopen.title.rejoin" : "hotwire.reopen.title.reopen");
    }

    static String findPlan(Offer offer) {
        if (offer.nextStart()) {
            return Lang.get("hotwire.reopen.plan.next_start");
        }
        if (offer.leaves()) {
            return Lang.get("hotwire.reopen.plan.leave");
        }
        if (offer.jar() != null) {
            return Lang.get("hotwire.reopen.plan.add");
        }
        return Lang.get(offer.ids().isEmpty() ? "hotwire.reopen.plan.reopen" : "hotwire.reopen.plan.switch");
    }
}
