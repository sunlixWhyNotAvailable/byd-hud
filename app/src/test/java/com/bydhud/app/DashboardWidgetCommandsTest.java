package com.bydhud.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class DashboardWidgetCommandsTest {
    @Test public void tbtReleasesOwnedContainerBeforeNativeAndTbtCommands() {
        List<Integer> commands = new ArrayList<>();
        assertEquals("", execute(NavAppDisplayController.WIDGET_MODE_TBT, 0,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER, commands, -1));
        assertEquals(List.of(18, 1, 2), commands);
        commands.clear();
        execute(NavAppDisplayController.WIDGET_MODE_TBT, 0,
                DashboardLayoutPolicy.OWNERSHIP_NONE, commands, -1);
        assertEquals(List.of(1, 2), commands);
    }

    @Test public void failedReleaseOrCancelledNativeCommandNeverSendsSuccessor() {
        for (int failure : new int[]{18, 1}) {
            List<Integer> commands = new ArrayList<>();
            assertEquals("failed or cancelled", execute(NavAppDisplayController.WIDGET_MODE_TBT,
                    0, DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER, commands, failure));
            assertEquals(failure == 18 ? List.of(18) : List.of(18, 1), commands);
        }
    }

    @Test public void ipcOffSendsOnlyTheNecessaryReleaseOrNativeCommand() {
        for (int ownership : new int[]{DashboardLayoutPolicy.OWNERSHIP_NONE,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER}) {
            List<Integer> commands = new ArrayList<>();
            execute(NavAppDisplayController.WIDGET_MODE_IPC_OFF, 0, ownership, commands, -1);
            assertEquals(List.of(ownership == DashboardLayoutPolicy.OWNERSHIP_NONE ? 1 : 18), commands);
        }
    }

    @Test public void nativeAndAlternativeLayoutsPreserveTheirDifferentReleaseRules() {
        for (int mode : new int[]{NavAppDisplayController.WIDGET_MODE_MINI,
                NavAppDisplayController.WIDGET_MODE_FULL}) {
            for (int command : new int[]{3, 4, 16, 17}) {
                List<Integer> commands = new ArrayList<>();
                execute(mode, command, DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER, commands, -1);
                assertEquals(command < 16 ? List.of(18, command) : List.of(command), commands);
            }
        }
    }

    @Test public void exceptionsAndLayoutFailureAreNotReplayedOrHidden() {
        List<Integer> commands = new ArrayList<>();
        assertEquals("failed or cancelled", execute(NavAppDisplayController.WIDGET_MODE_FULL,
                4, DashboardLayoutPolicy.OWNERSHIP_NONE, commands, 4));
        assertEquals(List.of(4), commands);
        assertThrows(IllegalStateException.class, () -> DashboardLayoutPolicy.executeWidget(
                NavAppDisplayController.WIDGET_MODE_TBT, 0, DashboardLayoutPolicy.OWNERSHIP_NONE,
                () -> "", command -> { throw new IllegalStateException("transport"); },
                () -> { throw new AssertionError("no layout after transport failure"); }));
    }

    private static String execute(int mode, int command, int ownership,
            List<Integer> commands, int failure) {
        java.util.function.IntFunction<String> send = value -> {
            commands.add(value);
            return value == failure ? "failed or cancelled" : "";
        };
        return DashboardLayoutPolicy.executeWidget(mode, command, ownership,
                () -> send.apply(18), send, () -> send.apply(command));
    }
}
