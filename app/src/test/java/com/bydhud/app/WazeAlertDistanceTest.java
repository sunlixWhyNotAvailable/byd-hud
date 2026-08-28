package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WazeAlertDistanceTest {
    @Test
    public void parsesUnicodeUnitsAndDecimalComma() {
        assertEquals(300, WazeDirectChannel.parseDistanceMeters("300 м"));
        assertEquals(300, WazeDirectChannel.parseDistanceMeters("300 М"));
        assertEquals(300, WazeDirectChannel.parseDistanceMeters("0,3 км"));
        assertEquals(300, WazeDirectChannel.parseDistanceMeters("0,3 КМ"));
        assertEquals(300, WazeDirectChannel.parseDistanceMeters("300 m"));
        assertEquals(1609, WazeDirectChannel.parseDistanceMeters("1 MI"));
        assertEquals(0, WazeDirectChannel.parseDistanceMeters("0 m"));
        assertEquals(-1, WazeDirectChannel.parseDistanceMeters("без відстані"));
    }
}
