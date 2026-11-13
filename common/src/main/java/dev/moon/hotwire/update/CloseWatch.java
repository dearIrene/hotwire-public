package dev.moon.hotwire.update;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CloseWatch {
    private static final System.Logger LOGGER = System.getLogger("Hotwire");
    private static final long MOST_SECONDS = 60;

    private CloseWatch() {
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Optional<ProcessHandle> game = ProcessHandle.of(Long.parseLong(args[0]));
        if (game.isEmpty()) {
            return;
        }
        try {
            game.get().onExit().get(MOST_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException stuck) {
            LOGGER.log(System.Logger.Level.INFO, "The game's exit began " + MOST_SECONDS + " s ago and its process "
                    + args[0] + " is still there: ended from outside.");
            game.get().destroyForcibly();
        }
    }
}
