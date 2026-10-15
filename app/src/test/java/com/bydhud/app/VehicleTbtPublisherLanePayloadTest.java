package com.bydhud.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class VehicleTbtPublisherLanePayloadTest {
    @Test public void amapDirectLanesKeepTheFirstEightBehavior() {
        VehicleTbtPublisher.LanePayload payload = VehicleTbtPublisher.lanePayloadForTest(
                directLanes(10), ManeuverCompatibilityProfile.AMAP);

        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, payload.directions);
    }

    @Test public void baiduDirectLanesKeepFirstFourAndLastFourWithoutShifting() {
        List<DirectTbtFrame.Lane> lanes = directLanes(10);
        lanes.set(5, null); // The omitted middle lanes do not affect the selected edges.
        VehicleTbtPublisher.LanePayload payload = VehicleTbtPublisher.lanePayloadForTest(
                lanes, ManeuverCompatibilityProfile.BAIDU);

        assertArrayEquals(new int[] {0, 1, 2, 3, 6, 7, 8, 9}, payload.directions);
        lanes.set(8, null); // A selected null slot clears the row instead of shifting lane 9.
        assertEquals(0, VehicleTbtPublisher.lanePayloadForTest(
                lanes, ManeuverCompatibilityProfile.BAIDU).directions.length);
        lanes.set(8, new DirectTbtFrame.Lane(-1, 255, "invalid"));
        assertEquals(0, VehicleTbtPublisher.lanePayloadForTest(
                lanes, ManeuverCompatibilityProfile.BAIDU).directions.length);
    }

    @Test public void manualBaiduLanesUseTheSameSelectionWhileAmapKeepsItsPrefix() {
        HudState state = new HudState();
        state.includeLaneBitmap = true;
        state.numOfLanes = 10;
        state.laneString = "S|L|R|U|S+L|R+U|L*|R|S|L*";

        VehicleTbtPublisher.LanePayload baidu = VehicleTbtPublisher.lanePayloadForTest(
                state, ManeuverCompatibilityProfile.BAIDU);
        VehicleTbtPublisher.LanePayload amap = VehicleTbtPublisher.lanePayloadForTest(
                state, ManeuverCompatibilityProfile.AMAP);

        assertArrayEquals(new int[] {0, 1, 3, 5, 1, 3, 0, 1}, baidu.directions);
        assertArrayEquals(new int[] {255, 255, 255, 255, 1, 255, 255, 1},
                baidu.recommendations);
        assertArrayEquals(new int[] {0, 1, 3, 5, 2, 17, 1, 3}, amap.directions);
    }

    private static List<DirectTbtFrame.Lane> directLanes(int count) {
        List<DirectTbtFrame.Lane> lanes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lanes.add(new DirectTbtFrame.Lane(index, 255, "lane-" + index));
        }
        return lanes;
    }
}
