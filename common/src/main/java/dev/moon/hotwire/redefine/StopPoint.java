package dev.moon.hotwire.redefine;

public final class StopPoint {
    private StopPoint() {
    }

    public static void check() throws RebuildException {
        if (ClassRebuilder.STOP.get().getAsBoolean()) {
            throw new RebuildException("the rebuild worked out ahead gave way to a switch",
                    new IllegalStateException("between two of its steps"));
        }
    }
}
