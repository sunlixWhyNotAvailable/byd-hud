package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NavAppMissingTaskTest {
    @Test public void inventoryFailureDoesNotMeanClosedTask() {
        assertTrue(new NavAppDisplayState("maps", -1, -1, false, "task missing").isConfirmedMissingTask());
        assertTrue(new NavAppDisplayState("waze", -1, -1, false, "Surface task missing").isConfirmedMissingTask());
        assertFalse(new NavAppDisplayState("maps", -1, -1, false,
                "check failed: IOException: shell disconnected").isConfirmedMissingTask());
        assertFalse(new NavAppDisplayState("maps", 42, 0, true, "task missing").isConfirmedMissingTask());
    }
}
