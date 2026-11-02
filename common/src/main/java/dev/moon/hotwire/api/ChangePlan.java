package dev.moon.hotwire.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ChangePlan {
    enum Reason {
        NONE,

        NOT_READY,

        NEEDS_MOD,

        NEEDS_RESTART,

        NEEDS_REOPEN,

        WORLD_LOCKED,

        REFUSED
    }

    boolean canApply();

    Reason getReason();

    List<String> getLines();

    CompletableFuture<SwitchResult> accept();

    boolean needsReopen();

    boolean canWaitForNextStart();

    CompletableFuture<SwitchResult> acceptAtNextStart();

    void cancel();
}
