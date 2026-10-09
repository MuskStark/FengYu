package fan.summer.fengyu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HeadlessLauncher.TrainingExitListener} contract: a training launch must exit on
 * its own with status 0 shortly after ready (the JVM's orderly exit path is what writes
 * the AOT/CDS dump), and the exit must be injectable so the test JVM is never killed.
 */
class HeadlessLauncherTrainingExitTest {

    @Test
    void listenerIsAnApplicationReadyListener() {
        // Registered via SpringApplicationBuilder.listeners(...): the builder only delivers
        // ApplicationReadyEvent to objects that implement this interface.
        assertInstanceOf(ApplicationListener.class, new HeadlessLauncher.TrainingExitListener());
    }

    @Test
    void exitsWithZeroAfterTheDelay() throws InterruptedException {
        AtomicInteger exited = new AtomicInteger(-1);
        HeadlessLauncher.TrainingExitListener listener =
                new HeadlessLauncher.TrainingExitListener(20, exited::set);

        long startedAt = System.currentTimeMillis();
        // The listener never reads the event payload; a bare fire is enough.
        listener.onApplicationEvent((ApplicationReadyEvent) null);

        long deadline = startedAt + 5_000;
        while (exited.get() == -1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(ExitCodes.SETUP_DONE, exited.get(),
                "training exit must use status 0 so trainers can recognize a clean dump");
    }

    @Test
    void productionListenerUsesTheDocumentedDelayAndRealExit() {
        // The no-args constructor is the production one; its delay is part of the trainer
        // contract (long enough for seeders + first health responses, short enough to keep
        // CI training fast). Pin it so a casual edit cannot silently desync the trainers.
        new HeadlessLauncher.TrainingExitListener(); // must not throw
        long exitDelayMillis = HeadlessLauncher.TrainingExitListener.EXIT_DELAY_MILLIS;
        assertTrue(exitDelayMillis >= 1_000 && exitDelayMillis <= 10_000,
                "exit delay out of its intended window: " + exitDelayMillis);
    }
}
