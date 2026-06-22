package com.bydhud.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class HudRoadPayload {
    private HudRoadPayload() {
    }

    static byte[] build(HudState state) {
        ByteArrayOutputStream road = new ByteArrayOutputStream();
        HudLaneModel.LaneSpec[] trustedLanes =
                state.includeLaneBitmap ? HudLaneModel.parse(state) : new HudLaneModel.LaneSpec[0];
        int trustedLaneCount = trustedLanes.length > 1 ? trustedLanes.length : 0;

        writeInt32(road, 2, state.crossStatus);
        writeInt32(road, 3, state.carToDestination);
        writeInt32(road, 4, state.timeToDestination);
        writeInt32(road, 5, trustedLaneCount);
        if (trustedLaneCount > 1) {
            byte[] laneBytes = HudGraphicPayload.buildLanePng(state);
            if (laneBytes.length > 0) {
                writeBytes(road, 7, laneBytes);
            }
        }
        if (state.turnBitmapMode != HudState.TURN_BITMAP_OMIT) {
            byte[] turnBytes = HudGraphicPayload.buildTurnPng(state);
            if (turnBytes.length > 0 || state.turnBitmapMode == HudState.TURN_BITMAP_EMPTY_FIELD) {
                writeBytes(road, 8, turnBytes);
            }
        }
        writeInt32(road, 9, state.distanceToIntersection);
        writeString(road, 10, state.roadName);
        writeInt32(road, 11, state.currentMaxSpeedLimit);
        writeInt32(road, 12, state.currentSpeed);
        writeInt32(road, 16, state.navigationStatus);
        if (state.includeNativeArrow) {
            writeInt32(road, 28, state.maneuverId);
        }
        if (shouldWriteLaneMetadata(state, trustedLaneCount)) {
            writeString(road, 29, HudLaneModel.field29Value(state));
        }
        writeString(road, 31, state.guidePoint);
        writeDouble(road, 33, state.navigationRatio);

        byte[] roadBytes = road.toByteArray();
        ByteArrayOutputStream wrapper = new ByteArrayOutputStream();
        writeTag(wrapper, 1, 2);
        writeVarint(wrapper, roadBytes.length);
        wrapper.write(roadBytes, 0, roadBytes.length);
        return wrapper.toByteArray();
    }

    static boolean shouldWriteLaneMetadata(HudState state) {
        return shouldWriteLaneMetadata(
                state,
                state.includeLaneBitmap ? HudLaneModel.parse(state).length : 0);
    }

    private static boolean shouldWriteLaneMetadata(HudState state, int trustedLaneCount) {
        return state.includeLaneBitmap
                && trustedLaneCount > 1
                && !HudLaneModel.hasMixedRecommendations(state)
                && !HudLaneModel.hasSmoothDirections(state)
                && !HudLaneModel.hasCustomLaneResources(state)
                && !HudLaneModel.hasRampDirections(state);
    }

    private static void writeInt32(ByteArrayOutputStream out, int field, int value) {
        writeTag(out, field, 0);
        writeVarint(out, value);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTag(out, field, 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] bytes) {
        writeTag(out, field, 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        writeTag(out, field, 1);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((bits >> (8 * i)) & 0xffL));
        }
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, (field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        long v = value & 0xffffffffL;
        while ((v & ~0x7fL) != 0L) {
            out.write((int) ((v & 0x7fL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
