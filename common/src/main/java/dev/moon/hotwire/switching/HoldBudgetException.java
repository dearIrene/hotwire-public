package dev.moon.hotwire.switching;

import java.util.concurrent.CancellationException;

public final class HoldBudgetException extends CancellationException {
    private static final long serialVersionUID = 1L;

    public HoldBudgetException() {
        super("the hold's budget ran out");
    }
}
