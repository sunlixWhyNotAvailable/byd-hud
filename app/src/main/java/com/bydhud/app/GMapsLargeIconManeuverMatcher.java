package com.bydhud.app;

import android.graphics.Bitmap;

final class GMapsLargeIconManeuverMatcher {
    private static final long ICON_TTL_MS = 5000L;
    private static final int MIN_CONFIDENCE = 70;

    private GMapsLargeIconManeuverMatcher() {
    }

    static NavManeuverEvidence match(Bitmap bitmap, long nowElapsedMs) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return NavManeuverEvidence.NONE;
        }
        Signature signature = Signature.from(bitmap);
        Known best = bestMatch(signature);
        if (best == null || best.confidence < MIN_CONFIDENCE) {
            return NavManeuverEvidence.NONE;
        }
        return NavManeuverEvidence.icon(best.maneuver, best.sourceManeuver, best.confidence,
                nowElapsedMs + ICON_TTL_MS, "largeIcon:" + best.name);
    }

    private static Known bestMatch(Signature signature) {
        Known best = null;
        for (Known candidate : KNOWN) {
            int distance = signature.hammingDistance(candidate.signature);
            int confidence = Math.max(0, 100 - (distance * 4));
            if (best == null || confidence > best.confidence) {
                best = new Known(candidate.name, candidate.maneuver,
                        candidate.sourceManeuver, candidate.signature, confidence);
            }
        }
        return best;
    }

    private static final Known[] KNOWN = new Known[] {
            Known.fromBits("right-fixture", NavSnapshot.Maneuver.RIGHT_90, 3,
                    "000000000000000"
                            + "000000100000000"
                            + "000000110000000"
                            + "000000111000000"
                            + "111111111100000"
                            + "111111111110000"
                            + "111111111100000"
                            + "000000111000000"
                            + "000000110000000"
                            + "000000100000000"
                            + "000000000000000"
                            + "000000000000000"
                            + "000000000000000"
                            + "000000000000000"
                            + "000000000000000"),
            Known.fromBits("left-fixture", NavSnapshot.Maneuver.LEFT_90, 4,
                    "000000000000000"
                            + "000000010000000"
                            + "000000110000000"
                            + "000001110000000"
                            + "000011111111111"
                            + "000111111111111"
                            + "000011111111111"
                            + "000001110000000"
                            + "000000110000000"
                            + "000000010000000"
                            + "000000000000000"
                            + "000000000000000"
                            + "000000000000000"
                            + "000000000000000"
                            + "000000000000000")
    };

    private static final class Known {
        final String name;
        final NavSnapshot.Maneuver maneuver;
        final int sourceManeuver;
        final Signature signature;
        final int confidence;

        Known(String name, NavSnapshot.Maneuver maneuver, int sourceManeuver,
                Signature signature, int confidence) {
            this.name = name;
            this.maneuver = maneuver;
            this.sourceManeuver = sourceManeuver;
            this.signature = signature;
            this.confidence = confidence;
        }

        static Known fromBits(String name, NavSnapshot.Maneuver maneuver,
                int sourceManeuver, String bits) {
            return new Known(name, maneuver, sourceManeuver, Signature.fromBits(bits), 100);
        }
    }

    static final class Signature {
        private static final int SIZE = 15;
        private final boolean[] bits;

        private Signature(boolean[] bits) {
            this.bits = bits;
        }

        static Signature from(Bitmap bitmap) {
            boolean[] bits = new boolean[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int px = Math.min(bitmap.getWidth() - 1, x * bitmap.getWidth() / SIZE);
                    int py = Math.min(bitmap.getHeight() - 1, y * bitmap.getHeight() / SIZE);
                    int color = bitmap.getPixel(px, py);
                    int alpha = (color >>> 24) & 0xff;
                    int red = (color >>> 16) & 0xff;
                    int green = (color >>> 8) & 0xff;
                    int blue = color & 0xff;
                    int brightness = (red + green + blue) / 3;
                    bits[y * SIZE + x] = alpha > 32 && brightness < 220;
                }
            }
            return new Signature(bits);
        }

        static Signature fromBits(String value) {
            if (value == null || value.length() != SIZE * SIZE) {
                throw new IllegalArgumentException("signature must be 225 bits");
            }
            boolean[] bits = new boolean[SIZE * SIZE];
            for (int i = 0; i < value.length(); i++) {
                bits[i] = value.charAt(i) == '1';
            }
            return new Signature(bits);
        }

        int hammingDistance(Signature other) {
            int distance = 0;
            for (int i = 0; i < bits.length; i++) {
                if (bits[i] != other.bits[i]) {
                    distance++;
                }
            }
            return distance;
        }
    }
}
