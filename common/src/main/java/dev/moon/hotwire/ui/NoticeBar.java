package dev.moon.hotwire.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class NoticeBar extends Element {
    private static final int WIDEST = 296;
    private static final int NARROWEST = 120;
    private static final int ONE_LINE = 26;
    private static final int TWO_LINES = 40;
    private static final int LINE = 11;
    private static final int TITLE_LINES = 2;
    private static final int DETAIL_LINES = 5;
    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int EDGE = 10;

    private int topInset;

    private static final int DRIFT = 28;
    private static final String CROSS = "×";

    private static final class Card {
        private Notice notice;

        private final EasedValue slide = EasedValue.ofDuration(0.22f);
        private final EasedValue top = EasedValue.ofDuration(0.18f);
        private final EasedValue hover = EasedValue.ofSpeed(14);

        private final EasedValue fade = EasedValue.ofSpeed(6);

        private boolean placed;
        private boolean leaving;
        private boolean dismissed;

        private int width = WIDEST;
        private int height;

        private int actionX;
        private int actionY;
        private int actionWidth;

        Card(Notice notice, int topAt, boolean fresh) {
            this.notice = notice;
            this.placed = !fresh;
            this.slide.snap(fresh ? 1 : 0);
            this.top.snap(topAt);
            this.fade.snap(1);
        }

        int getHeight() {
            return this.height > 0 ? this.height : this.notice.detail().isEmpty() ? ONE_LINE : TWO_LINES;
        }
    }

    private final List<Card> cards = new ArrayList<>();

    private @Nullable Runnable onDownload;

    private @Nullable Notice lastSaid;

    private boolean actionsShown = true;

    void hideActions() {
        this.actionsShown = false;
    }

    void dropOffer() {
        Notice said = this.lastSaid;
        if (said != null && said.action() != null) {
            if (Notices.getShowing().contains(said)) {
                Notices.drop(said);
            }
            this.lastSaid = null;
        }
    }

    boolean isEmpty() {
        return this.cards.isEmpty();
    }

    void openDownloadsWith(Runnable open) {
        this.onDownload = open;
    }

    private boolean opensDownloads(Card card) {
        return this.onDownload != null && card.notice.job() > 0 && card.notice.isUnderWay();
    }

    @Override
    protected boolean takesPointer() {
        return false;
    }

    void show(String message, boolean failure, String hint) {
        this.show(message, failure, hint, null);
    }

    void show(String message, boolean failure, String hint, Notice.@Nullable Action action) {
        String flat = message.replaceAll("\\s+", " ").strip();

        if (flat.isEmpty()) {
            Notice said = this.lastSaid;
            if (said != null) {
                Notices.drop(said);
                this.lastSaid = null;
            }
            return;
        }
        String title = MessageLine.getFirstSentence(flat);
        String detail = hint.isEmpty() ? flat.substring(title.length()).strip() : hint;
        Notice notice = (failure ? Notice.ofBad(title, detail) : Notice.ofDone(title, detail)).withAction(action);
        this.lastSaid = notice;
        Notices.show(notice);
    }

    @Override
    public void step(float dt) {
        List<Notice> showing = Notices.getShowing();
        for (int i = showing.size() - 1; i >= 0; i--) {
            Notice notice = showing.get(i);
            Card same = this.findShowing(notice.key());
            if (same == null) {
                this.cards.add(0, new Card(notice, EDGE + this.topInset, Notices.markSeen(notice.key())));
            } else if (!same.notice.equals(notice)) {
                same.notice = notice;
            }
        }
        int at = EDGE + this.topInset;
        for (Card card : this.cards) {
            if (!card.leaving && Notices.indexOf(showing, card.notice.key()) < 0) {
                card.leaving = true;
            }
            if (!card.leaving) {
                if (card.placed) {
                    card.top.snap(at);
                    card.placed = false;
                } else {
                    card.top.to(at);
                }
                at += card.getHeight() + GAP;

                float rest = !this.isPointerOn(card) && !card.notice.isUnderWay()
                        ? Notices.drain(card.notice.key(), dt) : Notices.findRestLeft(card.notice.key());
                if (rest <= 0) {
                    card.leaving = true;
                    Notices.drop(card.notice);
                }
            }
            card.slide.to(card.leaving && !card.dismissed ? 1 : 0);
            card.fade.to(card.dismissed ? 0 : 1);
            card.hover.to(!card.leaving && this.isPointerOn(card) ? 1 : 0);
            card.slide.step(dt);
            card.top.step(dt);
            card.fade.step(dt);
            card.hover.step(dt);
        }
        this.cards.removeIf(card -> card.leaving && (card.dismissed ? card.fade.getValue() < 0.01f
                : card.slide.getValue() > 0.995f));
        super.step(dt);
    }

    private @Nullable Card findShowing(String key) {
        for (Card card : this.cards) {
            if (!card.leaving && card.notice.key().equals(key)) {
                return card;
            }
        }
        return null;
    }

    @Override
    protected boolean onClick(double px, double py, int button) {
        for (Card card : this.cards) {
            Notice.Action action = card.notice.action();
            if (!card.leaving && action != null && card.actionWidth > 0 && px >= card.actionX - 2
                    && px < card.actionX + card.actionWidth + 2 && py >= card.actionY - 2
                    && py < card.actionY + LINE) {
                action.run().run();
            }
            if (!card.leaving && this.isOn(card, px, py) && this.opensDownloads(card)) {
                Objects.requireNonNull(this.onDownload).run();
                return true;
            }
            if (!card.leaving && this.isOn(card, px, py)) {
                card.leaving = true;
                card.dismissed = true;
                Notices.drop(card.notice);
                return true;
            }
        }
        return false;
    }

    private boolean isPointerOn(Card card) {
        return this.isOn(card, this.getPointerX(), this.getPointerY());
    }

    private boolean isOn(Card card, double px, double py) {
        int x = this.findX(card);
        int y = Math.round(card.top.getValue());
        return px >= x && px < x + card.width && py >= y && py < y + card.getHeight();
    }

    void setTopInset(int inset) {
        this.topInset = Math.max(0, inset);
    }

    private int findX(Card card) {
        return (int) Math.floor(this.findSlidX(card));
    }

    private float findSlidX(Card card) {
        return this.getWidth() - EDGE - card.width + card.slide.getValue() * (card.width + EDGE)
                + (1 - card.fade.getValue()) * DRIFT;
    }

    @Override
    protected void paintOver(Canvas canvas, float alpha) {
        for (int i = this.cards.size() - 1; i >= 0; i--) {
            Card card = this.cards.get(i);
            Notice notice = card.notice;
            boolean detailed = !notice.detail().isEmpty();
            Notice.Action action = this.actionsShown ? notice.action() : null;

            card.width = Math.max(NARROWEST, Math.min(WIDEST, 3 + PAD * 3 + Math.max(
                    canvas.measureText(notice.title()), detailed ? canvas.measureText(notice.detail()) : 0)));
            int w = card.width;
            List<String> titleLines = MessageLine.wrap(canvas, notice.title(), w - 3 - PAD * 3, TITLE_LINES);
            List<String> detailLines = detailed ? MessageLine.wrap(canvas, notice.detail(), w - 3 - PAD * 3,
                    DETAIL_LINES) : List.of();
            card.height = 15 + titleLines.size() * LINE + (detailed ? 3 + detailLines.size() * LINE : 0)
                    + (action == null ? 0 : 3 + LINE);
            int h = card.height;
            float slidX = this.findSlidX(card);
            int x = (int) Math.floor(slidX);
            int y = (int) Math.floor(card.top.getValue());
            if (x >= this.getWidth()) {
                continue;
            }
            canvas.pushShift(slidX - x, card.top.getValue() - y);
            int kind = notice.kind().getColor();
            float hover = card.hover.getValue();
            float a = alpha * card.fade.getValue();
            canvas.fill(x - 1, y - 1, x + w + 1, y + h + 1, Colors.fade(Colors.mix(Palette.PANEL_EDGE, kind,
                    0.7f * hover), a));
            canvas.fill(x, y, x + w, y + h, Colors.fade(Colors.mix(Palette.PANEL, Palette.PANEL_EDGE, 0.5f * hover),
                    a));
            canvas.fill(x, y, x + 3, y + h, Colors.fade(kind, a));
            int textX = x + 3 + PAD;
            int room = w - 3 - PAD * 3;
            if (hover > 0.01f && !this.opensDownloads(card)) {
                canvas.drawText(CROSS, x + w - PAD - canvas.measureText(CROSS), y + 5,
                        Colors.fade(Colors.mix(Palette.FAINT, Palette.TEXT, hover), a * hover));
            }
            int lineY = y + 9;
            for (String line : titleLines) {
                canvas.drawText(canvas.clip(line, room), textX, lineY, Colors.fade(detailed ? kind : Palette.TEXT, a));
                lineY += LINE;
            }
            lineY += 3;

            boolean red = false;
            for (String line : detailLines) {
                String said = hover > 0.5f && this.opensDownloads(card) ? Lang.get("hotwire.notice.download_manage")
                        : line;
                int partX = textX;
                for (String part : canvas.clip(said, room).split("\\*", -1)) {
                    canvas.drawText(part, partX, lineY, Colors.fade(red ? Palette.OFF_TINT : Palette.DIM, a));
                    partX += canvas.measureText(part);
                    red = !red;
                }
                red = !red;
                lineY += LINE;
            }
            card.actionWidth = 0;
            if (action != null) {
                lineY += detailed ? 3 : 0;
                card.actionX = textX;
                card.actionY = lineY;
                card.actionWidth = canvas.measureText(action.label());
                int ink = Colors.fade(Palette.ACCENT, a);
                canvas.drawText(action.label(), textX, lineY, ink);
                canvas.fill(textX, lineY + Text.HEIGHT, textX + card.actionWidth, lineY + Text.HEIGHT + 1, ink);
            }
            if (notice.isUnderWay()) {
                canvas.fill(x + 3, y + h - 2, x + w, y + h, Colors.fade(Palette.TRACK, a));
                canvas.fill(x + 3, y + h - 2, x + 3 + Math.round((w - 3) * notice.progress()), y + h,
                        Colors.fade(kind, a));
            } else {
                int drained = Math.round((w - 3) * Math.max(0, Math.min(1,
                        Notices.findRestLeft(notice.key()) / notice.findRest())));
                canvas.fill(x + 3, y + h - 1, x + 3 + drained, y + h,
                        Colors.fade(Colors.mix(Palette.PANEL, kind, 0.5f), a));
            }
            canvas.popShift();
        }
    }
}
