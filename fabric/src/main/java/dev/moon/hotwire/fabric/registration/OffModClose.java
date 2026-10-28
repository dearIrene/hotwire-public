package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.RegistrationJournal;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffModClose {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffModClose.class);

    private OffModClose() {
    }

    private static final long EXIT_AFTER_MILLIS = 10_000;

    public static void exitWhenStuck() {
        Thread game = Thread.currentThread();
        Thread watch = new Thread(() -> {
            try {
                game.join();
                Thread.sleep(EXIT_AFTER_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean anyOff = GameRegistrations.getJournal().countByOwner().keySet().stream()
                    .anyMatch(Tripwire::isWatched);
            if (anyOff) {
                LOGGER.warn("[Hotwire] the game closed, yet {} kept it up {} s on: it exits now",
                        Thread.getAllStackTraces().keySet().stream().filter(thread -> !thread.isDaemon()
                                && thread.isAlive() && !thread.getName().equals("DestroyJavaVM"))
                                .map(Thread::getName).toList(), EXIT_AFTER_MILLIS / 1000);
                System.exit(0);
            }
        }, "hotwire-close-watch");
        watch.setDaemon(true);
        watch.start();
    }

    public static void run(Minecraft client) {
        RegistrationJournal journal = GameRegistrations.getJournal();
        for (String mod : journal.countByOwner().keySet()) {
            if (!Tripwire.isWatched(mod)) {
                continue;
            }
            for (RegistrationJournal.Entry entry : journal.getEntries(mod)) {
                if (entry.registration() instanceof EventRegistration event
                        && event.listensTo(ClientLifecycleEvents.CLIENT_STOPPING) && event.isOutOfEvent()
                        && event.findListener() instanceof ClientLifecycleEvents.ClientStopping stopping) {
                    Tripwire.runAsHotwire(() -> {
                        try {
                            stopping.onClientStopping(client);
                        } catch (RuntimeException | LinkageError e) {
                            LOGGER.info("[Hotwire] {}'s listener for the game's close threw: {}", mod, e.toString());
                        }
                    });
                }
            }
        }
    }
}
