package dev.moon.hotwire.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class BrokenModSearch {
    public sealed interface Result {
        record Found(String id) implements Result {
        }

        record Tied(List<String> ids) implements Result {
            public Tied {
                ids = List.copyOf(ids);
            }
        }

        record NotAmong() implements Result {
        }
    }

    private final List<String> order = new ArrayList<>();

    private final List<Integer> cuts = new ArrayList<>();

    private int low;
    private int high;
    private int at;
    private int answered;
    private boolean further;

    public BrokenModSearch(Collection<String> mods, Function<String, ? extends Collection<String>> goesWith) {
        Set<String> all = new LinkedHashSet<>(mods);
        Set<String> seen = new LinkedHashSet<>();
        all.forEach(mod -> this.place(mod, all, goesWith, seen));
        int reach = 0;
        this.cuts.add(0);
        for (int index = 0; index < this.order.size(); index++) {
            for (String other : findAllGoing(this.order.get(index), goesWith)) {
                reach = Math.max(reach, this.order.indexOf(other) + 1);
            }
            if (reach <= index + 1) {
                this.cuts.add(index + 1);
            }
        }
        this.high = this.cuts.size();
        this.at = this.pick();
    }

    public static Set<String> findAllGoing(String mod, Function<String, ? extends Collection<String>> goesWith) {
        Set<String> going = new LinkedHashSet<>(List.of(mod));
        Deque<String> next = new ArrayDeque<>(going);
        while (!next.isEmpty()) {
            for (String other : goesWith.apply(next.poll())) {
                if (going.add(other)) {
                    next.add(other);
                }
            }
        }
        return going;
    }

    private void place(String mod, Set<String> all, Function<String, ? extends Collection<String>> goesWith,
            Set<String> seen) {
        if (!seen.add(mod)) {
            return;
        }
        for (String other : findAllGoing(mod, goesWith)) {
            if (all.contains(other)) {
                this.place(other, all, goesWith, seen);
            }
        }
        this.order.add(mod);
    }

    public static int countRounds(int mods) {
        return mods <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(mods - 1);
    }

    private int pick() {
        int last = this.cuts.size() - 1;
        return Math.min(last, Math.max(this.low + 1, (this.low + Math.min(this.high, last)) / 2));
    }

    public boolean isDone() {
        return this.high - this.low <= 1;
    }

    public List<String> getOff() {
        return List.copyOf(this.order.subList(0, this.cuts.get(this.at)));
    }

    public List<String> getOffIfFurther() {
        if (this.isDone() || this.high - this.at <= 1) {
            return this.getOff();
        }
        int last = this.cuts.size() - 1;
        int next = Math.min(last, Math.max(this.at + 1, (this.at + Math.min(this.high, last)) / 2));
        return List.copyOf(this.order.subList(0, this.cuts.get(next)));
    }

    public List<String> getSuspects() {
        int end = Math.min(this.high, this.cuts.size() - 1);
        return List.copyOf(this.order.subList(this.cuts.get(Math.min(this.low, end)), this.cuts.get(end)));
    }

    public List<String> getOrder() {
        return List.copyOf(this.order);
    }

    public int getRound() {
        return this.answered + 1;
    }

    public int countRoundsLeft() {
        int end = Math.min(this.high, this.cuts.size() - 1);
        return this.isDone() ? 0 : Math.max(1, countRounds(end - this.low));
    }

    public boolean isLastCheck() {
        return !this.isDone() && this.high == this.cuts.size() && this.at == this.cuts.size() - 1;
    }

    public boolean isFurtherOn() {
        return this.further;
    }

    public void answer(boolean stillThere) {
        if (this.isDone()) {
            return;
        }
        this.further = stillThere;
        if (stillThere) {
            this.low = this.at;
        } else {
            this.high = this.at;
        }
        this.answered++;
        if (!this.isDone()) {
            this.at = this.pick();
        }
    }

    public @Nullable Result getResult() {
        if (!this.isDone()) {
            return null;
        }
        if (this.high == this.cuts.size()) {
            return new Result.NotAmong();
        }
        List<String> left = this.order.subList(this.cuts.get(this.low), this.cuts.get(this.high));
        return left.size() == 1 ? new Result.Found(left.getFirst()) : new Result.Tied(left);
    }
}
