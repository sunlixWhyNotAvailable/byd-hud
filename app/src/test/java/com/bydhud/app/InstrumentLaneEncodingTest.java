package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class InstrumentLaneEncodingTest {
    @Test public void baiduMapsSingleDirectionsAndRecommendationsToOemIds() {
        InstrumentLaneEncoding.Encoding encoded = InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU,
                new int[] {0, 0, 1, 3, 5, 5},
                new int[] {255, 0, 255, 3, 255, 5});

        assertBaiduBatch(encoded, new int[] {97, 96, 101, 103, 99, 98});
    }

    @Test public void baiduPreservesMixedPhysicalLanesAndRecommendationSubsets() {
        InstrumentLaneEncoding.Encoding encoded = InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU,
                new int[] {2, 7, 2, 3, 0},
                new int[] {0, 255, 2, 3, 255});

        assertBaiduBatch(encoded, new int[] {104, 128, 107, 103, 97});
    }

    @Test public void capturedWazeDirectionsUseTheExactBaiduLaneTable() {
        InstrumentLaneEncoding.Encoding encoded = InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU,
                new int[] {5, 11, 0, 4},
                new int[] {255, 255, 0, 0});

        assertBaiduBatch(encoded, new int[] {99, 112, 96, 109});
    }

    @Test public void baiduTableCoversEverySupportedCompactDirectionAndSubset() {
        int[][] cases = {
                {0, 255, 97}, {0, 0, 96}, {1, 255, 101}, {1, 1, 100},
                {3, 255, 102}, {3, 3, 103}, {5, 255, 99}, {5, 5, 98},
                {2, 255, 105}, {2, 0, 104}, {2, 1, 106}, {2, 2, 107},
                {4, 255, 108}, {4, 0, 109}, {4, 3, 110}, {4, 4, 111},
                {6, 255, 125}, {6, 1, 124}, {6, 3, 126}, {6, 6, 127},
                {9, 255, 120}, {9, 0, 122}, {9, 5, 121}, {9, 9, 123},
                {11, 255, 112}, {11, 1, 114}, {11, 5, 113}, {11, 11, 115},
                {17, 255, 116}, {17, 3, 117}, {17, 5, 118}, {17, 17, 119},
                {7, 255, 128}, {7, 0, 129}, {7, 1, 130}, {7, 3, 131}, {7, 7, 132},
                {16, 255, 138}, {16, 0, 140}, {16, 1, 141}, {16, 5, 139}, {16, 16, 142},
                {19, 255, 143}, {19, 0, 146}, {19, 3, 145}, {19, 5, 144}, {19, 19, 147},
                {18, 255, 133}, {18, 1, 136}, {18, 3, 135}, {18, 5, 134}, {18, 18, 137}
        };

        for (int[] testCase : cases) {
            InstrumentLaneEncoding.Encoding encoded = InstrumentLaneEncoding.encode(
                    ManeuverCompatibilityProfile.BAIDU,
                    new int[] {testCase[0]}, new int[] {testCase[1]});
            assertBaiduBatch(encoded, new int[] {testCase[2]});
        }
    }

    @Test public void baiduClearsWholeBatchForEmptyOrUnrepresentableLanes() {
        assertBaiduBatch(InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU, new int[0], new int[0]), new int[0]);
        assertCleared(InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU, new int[] {0, 8}, new int[] {0, 255}),
                "right-U direction");
        assertCleared(InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU, new int[] {0, 13}, new int[] {0, 255}),
                "unsupported direction");
        assertCleared(InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU, new int[] {0}, new int[] {1}),
                "recommendation outside direction");
        assertCleared(InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU, new int[] {0}, new int[] {8}),
                "right-U recommendation");
        assertCleared(InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.BAIDU, new int[] {7}, new int[] {2}),
                "unsupported recommendation subset");
    }

    @Test public void amapBatchRetainsItsPreviousCodesAndAbsentValues() {
        InstrumentLaneEncoding.Encoding encoded = InstrumentLaneEncoding.encode(
                ManeuverCompatibilityProfile.AMAP,
                new int[] {0, 2, 11},
                new int[] {0, 1, 1});

        assertEquals("", encoded.issue);
        assertArrayEquals(expectedFeatureIds(), encoded.featureIds);
        assertArrayEquals(new int[] {
                3,
                26, 52, 64, -1, -1, -1, -1, -1,
                0, 0, 0, 14, 14, 14, 14, 14,
                1, 1, 1, -1, -1, -1, -1, -1
        }, encoded.values);
    }

    private static void assertCleared(InstrumentLaneEncoding.Encoding encoded, String issue) {
        assertEquals(issue, encoded.issue);
        assertBaiduWireBatch(encoded, new int[0]);
    }

    private static void assertBaiduBatch(
            InstrumentLaneEncoding.Encoding encoded, int[] expectedLaneIds) {
        assertEquals("", encoded.issue);
        assertBaiduWireBatch(encoded, expectedLaneIds);
    }

    private static void assertBaiduWireBatch(
            InstrumentLaneEncoding.Encoding encoded, int[] expectedLaneIds) {
        assertArrayEquals(expectedFeatureIds(), encoded.featureIds);
        assertEquals(expectedLaneIds.length, encoded.values[0]);
        for (int index = 0; index < 8; index++) {
            assertEquals(index < expectedLaneIds.length ? expectedLaneIds[index] : 0,
                    encoded.values[index + 1]);
            assertEquals(15, encoded.values[index + 9]);
            assertEquals(0, encoded.values[index + 17]);
        }
    }

    private static int[] expectedFeatureIds() {
        return new int[] {
                427827416,
                427827288, 427827304, 427827320, 427827336,
                427827352, 427827368, 427827384, 427827400,
                427827296, 427827312, 427827328, 427827344,
                427827360, 427827376, 427827392, 427827408,
                427827300, 427827316, 427827332, 427827348,
                427827364, 427827380, 427827396, 427827412
        };
    }
}
