package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Rectangle;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the REAL {@link AwtComputerDriver} against the host display. Opt-in only
 * ({@code -Dfengyu.local-it=true}) because CI is headless and this captures the actual screen.
 * Deliberately limited to observing operations — no input injection, so running it never
 * disturbs the interactive session.
 */
@EnabledIfSystemProperty(named = "fengyu.local-it", matches = "true")
class AwtComputerDriverLocalIT {

    @Test
    void realDisplaySupportsCapture() {
        ComputerDriver driver = AwtComputerDriver.create();
        assertTrue(driver.available(), "reason: " + driver.unavailableReason());

        var displays = driver.displays();
        assertFalse(displays.isEmpty());
        assertTrue(displays.stream().anyMatch(ComputerDriver.DisplayInfo::primary));

        var primary = displays.stream().filter(ComputerDriver.DisplayInfo::primary).findFirst().orElseThrow();
        Rectangle bounds = new Rectangle(primary.x(), primary.y(),
                Math.min(primary.width(), 400), Math.min(primary.height(), 300));
        ComputerDriver.Capture capture = driver.capture(bounds);
        assertTrue(capture.png().length > 100, "PNG payload expected");
        // PNG signature (the same check ToolMediaBridge applies before feeding the model).
        byte[] png = capture.png();
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        assertTrue(capture.imageWidth() > 0 && capture.imageHeight() > 0);
        assertTrue(capture.scale() > 0);
        // Decodable base64 of a real capture round-trips.
        assertEquals(png.length, Base64.getDecoder().decode(Base64.getEncoder().encodeToString(png)).length);

        var cursor = driver.mousePosition();
        assertTrue(cursor.x() >= primary.x() && cursor.y() >= primary.y());
    }
}
