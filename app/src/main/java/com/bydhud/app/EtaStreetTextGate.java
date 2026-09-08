package com.bydhud.app;

/** Pure first-pass gate for ETA changes embedded in the RoadInfo street field. */
final class EtaStreetTextGate {
    private String contextKey;
    private String sourceRoad;
    private String sentText;
    private String pendingText;
    private String event = "";
    private long holdUntilMs;
    private boolean observed;
    private boolean eligible;
    private boolean selectedEligible;

    String select(String candidateText, String sourceRoad, String contextKey,
            boolean eligible, long nowMs) {
        String candidate = safe(candidateText);
        String road = safe(sourceRoad);
        String context = safe(contextKey);
        selectedEligible = false;

        if (!observed) {
            observed = true;
            this.eligible = eligible;
            this.contextKey = context;
            this.sourceRoad = road;
        } else if (eligible != this.eligible) {
            clearGateState();
            event = "RESET reason=eligibility";
            this.eligible = eligible;
            this.contextKey = context;
            this.sourceRoad = road;
        } else if (!context.equals(this.contextKey)) {
            clearGateState();
            event = "RESET reason=context";
            this.contextKey = context;
            this.sourceRoad = road;
        } else if (!road.equals(this.sourceRoad)) {
            this.sourceRoad = road;
            pendingText = null;
            holdUntilMs = 0;
            event = "RESET reason=road";
        }
        if (!eligible) return candidate;
        selectedEligible = true;

        if (sentText == null) return candidate;

        boolean holding = holdUntilMs > nowMs;
        if (candidate.equals(sentText)) {
            pendingText = null;
        } else if (holding || pendingText != null) {
            pendingText = candidate;
        }
        if (holding) return sentText;

        holdUntilMs = 0;
        if (pendingText != null) {
            String released = pendingText;
            pendingText = null;
            event = "RELEASE release_ready";
            return released;
        }
        return candidate;
    }

    void onSent(String actuallySentText, long nowMs) {
        if (!selectedEligible) return;
        selectedEligible = false;
        String actual = safe(actuallySentText);
        if (actual.equals(sentText)) return;
        sentText = actual;
        pendingText = null;
        long hold = holdMillis(actual);
        holdUntilMs = hold == 0 ? 0 : nowMs + hold;
        if (hold > 0) {
            event = "START initial_pass width=" + estimatedWidth(actual) + " holdMs=" + hold;
        }
    }

    void reset() {
        clearGateState();
        observed = false;
        eligible = false;
        contextKey = null;
        sourceRoad = null;
        selectedEligible = false;
        event = "RESET reason=explicit";
    }

    String drainEvent() {
        String drained = event;
        event = "";
        return drained;
    }

    static double estimatedWidth(String text) {
        if (text == null) return 0;
        double width = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == 'i' || value == 'I' || value == '\u0456' || value == '\u0406') {
                width += 6.5;
            } else if (value == 'w') {
                width += 19;
            } else if (value == 'W') {
                width += 24;
            } else if (Character.isLowerCase(value)) {
                width += 12;
            } else if (Character.isUpperCase(value)) {
                width += 15;
            } else if (Character.isDigit(value)) {
                width += 12;
            } else {
                width += 5;
            }
        }
        return width;
    }

    static long holdMillis(String text) {
        double width = estimatedWidth(text);
        return width <= 100 ? 0 : (long) Math.ceil(2 * width / 100 + 0.5) * 1000;
    }

    private void clearGateState() {
        sentText = null;
        pendingText = null;
        holdUntilMs = 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
