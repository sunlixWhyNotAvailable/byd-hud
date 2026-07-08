package com.bydhud.app;

//keeps lane semantics compact so Waze and Google Maps can share HUD lane decisions.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

//defines the HudLaneModel module boundary so related behavior stays readable inside one unit.
final class HudLaneModel {
    static final int MAX_LANES = 8;
    private static final int ICON_SMOOTH_LEFT = 90;
    private static final int ICON_SMOOTH_RIGHT = 91;
    private static final int ICON_RAMP_RIGHT = 70;
    private static final int ICON_RAMP_LEFT = 71;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudLaneModel() {
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static LaneSpec[] parse(HudState state) {
        String[] tokens = splitTokens(state.laneString);
        if (tokens.length == 0) {
            return new LaneSpec[0];
        }
        if (state.numOfLanes > 0 && state.numOfLanes != tokens.length) {
            return new LaneSpec[0];
        }
        int count = Math.max(0, Math.min(MAX_LANES, tokens.length));
        if (count == 0) {
            return new LaneSpec[0];
        }

        LaneSpec[] lanes = new LaneSpec[count];
        for (int i = 0; i < count; i++) {
            lanes[i] = parseLane(tokens[i], i, count);
        }
        return lanes;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String signature(HudState state) {
        LaneSpec[] lanes = parse(state);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lanes.length; i++) {
            if (i > 0) {
                out.append('|');
            }
            out.append(lanes[i].label);
        }
        return out.toString();
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String field29Value(HudState state) {
        LaneSpec[] lanes = parse(state);
        StringBuilder out = new StringBuilder();
        for (LaneSpec lane : lanes) {
            out.append(lane.iconId)
                    .append(',')
                    .append(lane.recommended ? lane.iconId : 255)
                    .append('|');
        }
        return out.toString();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasMixedRecommendations(HudState state) {
        LaneSpec[] lanes = parse(state);
        for (LaneSpec lane : lanes) {
            if (lane.hasMixedRecommendations()) {
                return true;
            }
        }
        return false;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasSmoothDirections(HudState state) {
        LaneSpec[] lanes = parse(state);
        for (LaneSpec lane : lanes) {
            if (lane.hasSmoothDirection()) {
                return true;
            }
        }
        return false;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasRampDirections(HudState state) {
        LaneSpec[] lanes = parse(state);
        for (LaneSpec lane : lanes) {
            if (lane.hasRampDirection()) {
                return true;
            }
        }
        return false;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasCustomLaneResources(HudState state) {
        LaneSpec[] lanes = parse(state);
        for (LaneSpec lane : lanes) {
            if (lane.hasCustomLaneResource()) {
                return true;
            }
        }
        return false;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static LaneSpec parseLane(String rawToken, int index, int count) {
        String token = rawToken == null ? "" : rawToken.trim();
        token = token.replace(" ", "");
        boolean recommended = token.endsWith("*") || token.endsWith("!") || token.startsWith("*")
                || token.startsWith("!");

        if (token.contains("+")) {
            LaneSpec compound = parseCompoundLane(token);
            if (compound != null) {
                return compound;
            }
        }

        token = token.replace("*", "").replace("!", "");
        String canonical = canonicalToken(token);

        if (canonical.contains(",")) {
            LaneSpec numeric = parseNumericLane(canonical, recommended);
            if (numeric != null) {
                return numeric;
            }
        }

        if (canonical.isEmpty()) {
            return defaultLane(index, count);
        }
        if ("S".equals(canonical) || "STRAIGHT".equals(canonical) || "UP".equals(canonical)) {
            return new LaneSpec(0, recommended, label("S", recommended));
        }
        if ("L".equals(canonical) || "LEFT".equals(canonical)) {
            return new LaneSpec(1, recommended, label("L", recommended));
        }
        if ("Ls".equals(canonical)) {
            return new LaneSpec(ICON_SMOOTH_LEFT, recommended, label("Ls", recommended));
        }
        if ("SL".equals(canonical) || "LS".equals(canonical) || "LEFTSTRAIGHT".equals(canonical)) {
            return new LaneSpec(2, recommended, label("S+L", recommended));
        }
        if ("R".equals(canonical) || "RIGHT".equals(canonical)) {
            return new LaneSpec(3, recommended, label("R", recommended));
        }
        if ("Rs".equals(canonical)) {
            return new LaneSpec(ICON_SMOOTH_RIGHT, recommended, label("Rs", recommended));
        }
        if ("RampL".equals(canonical)) {
            return recommended
                    ? new LaneSpec(ICON_RAMP_LEFT, true, "RampL*")
                    : new LaneSpec(22, false, "DASH");
        }
        if ("RampR".equals(canonical)) {
            return recommended
                    ? new LaneSpec(ICON_RAMP_RIGHT, true, "RampR*")
                    : new LaneSpec(22, false, "DASH");
        }
        if ("SR".equals(canonical) || "RS".equals(canonical) || "RIGHTSTRAIGHT".equals(canonical)) {
            return new LaneSpec(4, recommended, label("S+R", recommended));
        }
        if ("U".equals(canonical) || "UL".equals(canonical) || "UTURN".equals(canonical)) {
            return new LaneSpec(5, recommended, label("U", recommended));
        }
        if ("LR".equals(canonical) || "RL".equals(canonical)) {
            return new LaneSpec(6, recommended, label("LR", recommended));
        }
        if ("SLR".equals(canonical) || "SRL".equals(canonical) || "LSR".equals(canonical)
                || "LRS".equals(canonical) || "RSL".equals(canonical) || "RLS".equals(canonical)) {
            return new LaneSpec(7, recommended, label("SLR", recommended));
        }
        if ("UR".equals(canonical) || "RUTURN".equals(canonical)) {
            return new LaneSpec(8, recommended, label("UR", recommended));
        }
        if ("BUS".equals(canonical)) {
            return new LaneSpec(15, recommended, label("BUS", recommended));
        }
        if ("DASH".equals(canonical) || "NONE".equals(canonical) || "EMPTY".equals(canonical)) {
            return new LaneSpec(22, false, "DASH");
        }

        return defaultLane(index, count);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static LaneSpec parseNumericLane(String token, boolean forcedRecommended) {
        String[] parts = token.split(",", -1);
        if (parts.length < 1) {
            return null;
        }
        try {
            int back = Integer.parseInt(parts[0]);
            int front = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 255;
            int icon = front != 255 ? front : back;
            boolean recommended = forcedRecommended || (front != 255 && front == back);
            return new LaneSpec(icon, recommended, label("I" + icon, recommended));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static LaneSpec defaultLane(int index, int count) {
        int centerLeft = (count - 1) / 2;
        int centerRight = count / 2;
        boolean recommended = index == centerLeft || index == centerRight;
        return new LaneSpec(0, recommended, label("S", recommended));
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static LaneSpec parseCompoundLane(String token) {
        String[] rawParts = token.split("\\+", -1);
        if (rawParts.length < 2 || rawParts.length > 3) {
            return null;
        }
        LanePart[] parts = new LanePart[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            parts[i] = LanePart.parse(rawParts[i]);
            if (parts[i] == null) {
                return null;
            }
        }

        int iconId = compoundIconId(parts);
        if (iconId < 0) {
            return null;
        }

        boolean recommended = false;
        StringBuilder label = new StringBuilder();
        for (LanePart part : parts) {
            if (label.length() > 0) {
                label.append('+');
            }
            label.append(part.label());
            recommended = recommended || part.recommended;
        }
        return new LaneSpec(iconId, recommended, label.toString(), parts);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static int compoundIconId(LanePart[] parts) {
        if (parts.length == 2) {
            LanePart left = parts[0];
            LanePart right = parts[1];
            if (left.is("S") && right.is("L")) {
                return 2;
            }
            if (left.is("S") && right.is("R")) {
                return 4;
            }
            if (left.is("L") && right.is("R")) {
                return 6;
            }
            if (left.is("S") && right.is("Ls")) {
                return ICON_SMOOTH_LEFT;
            }
            if (left.is("S") && right.is("Rs")) {
                return ICON_SMOOTH_RIGHT;
            }
            if (left.is("Ls") && right.is("L")) {
                return ICON_SMOOTH_LEFT;
            }
            if (left.is("Rs") && right.is("R")) {
                return ICON_SMOOTH_RIGHT;
            }
            if (left.is("L") && right.is("Rs")) {
                return ICON_SMOOTH_RIGHT;
            }
            if (left.is("Ls") && right.is("R")) {
                return ICON_SMOOTH_LEFT;
            }
            return -1;
        }
        if (parts.length == 3
                && parts[0].is("S")
                && parts[1].is("L")
                && parts[2].is("R")) {
            return 7;
        }
        return -1;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static String label(String base, boolean recommended) {
        return recommended ? base + "*" : base;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static String[] splitTokens(String laneString) {
        if (laneString == null || laneString.trim().isEmpty()) {
            return new String[0];
        }
        String[] raw = laneString.split("\\|", -1);
        List<String> tokens = new ArrayList<>();
        for (String token : raw) {
            if (!token.trim().isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens.toArray(new String[0]);
    }

    //defines the LaneSpec module boundary so related behavior stays readable inside one unit.
    static final class LaneSpec {
        final int iconId;
        final boolean recommended;
        final String label;
        final LanePart[] parts;
        final String customResourceName;

        LaneSpec(int iconId, boolean recommended, String label) {
            this(iconId, recommended, label, partsFromLabel(label));
        }

        LaneSpec(int iconId, boolean recommended, String label, LanePart[] parts) {
            this(iconId, recommended, label, parts, customResourceName(label));
        }

        LaneSpec(int iconId, boolean recommended, String label, LanePart[] parts, String customResourceName) {
            this.iconId = iconId;
            this.recommended = recommended;
            this.label = label;
            this.parts = parts == null ? new LanePart[0] : parts;
            this.customResourceName = customResourceName == null ? "" : customResourceName;
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean hasMixedRecommendations() {
            if (parts.length <= 1) {
                return false;
            }
            boolean first = parts[0].recommended;
            for (LanePart part : parts) {
                if (part.recommended != first) {
                    return true;
                }
            }
            return false;
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean hasSmoothDirection() {
            if ("Ls".equals(label.replace("*", "").replace("!", ""))
                    || "Rs".equals(label.replace("*", "").replace("!", ""))) {
                return true;
            }
            for (LanePart part : parts) {
                if (part.isSmooth()) {
                    return true;
                }
            }
            return false;
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean hasRampDirection() {
            String clean = label.replace("*", "").replace("!", "");
            return "RampL".equals(clean) || "RampR".equals(clean);
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean hasCustomLaneResource() {
            return customResourceName.startsWith("global_image_landcustom_hud_");
        }
    }

    //defines the LanePart module boundary so related behavior stays readable inside one unit.
    static final class LanePart {
        final String token;
        final boolean recommended;

        LanePart(String token, boolean recommended) {
            this.token = token;
            this.recommended = recommended;
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LanePart parse(String rawPart) {
            String raw = rawPart == null ? "" : rawPart.trim();
            boolean recommended = raw.startsWith("*") || raw.startsWith("!")
                    || raw.endsWith("*") || raw.endsWith("!");
            String token = raw.replace("*", "").replace("!", "");
            token = canonicalToken(token);
            if (!"S".equals(token) && !"L".equals(token) && !"R".equals(token)
                    && !"Ls".equals(token) && !"Rs".equals(token)) {
                return null;
            }
            return new LanePart(token, recommended);
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean is(String expected) {
            return expected.equals(token);
        }

        //keeps this HUD step isolated so cluster payload behavior stays predictable.
        String label() {
            return HudLaneModel.label(token, recommended);
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean isSmooth() {
            return "Ls".equals(token) || "Rs".equals(token);
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static String canonicalToken(String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim().replace(" ", "");
        if ("Ls".equals(token) || "ls".equals(token)) {
            return "Ls";
        }
        if ("Rs".equals(token) || "rs".equals(token)) {
            return "Rs";
        }
        if ("RampL".equals(token) || "rampl".equals(token)) {
            return "RampL";
        }
        if ("RampR".equals(token) || "rampr".equals(token)) {
            return "RampR";
        }
        return token.toUpperCase(Locale.ROOT);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static LanePart[] partsFromLabel(String label) {
        String clean = label == null ? "" : label.trim();
        if (clean.isEmpty() || clean.contains("I") || clean.contains("U")
                || clean.contains("BUS") || clean.contains("DASH")) {
            return new LanePart[0];
        }
        String[] rawParts = clean.contains("+")
                ? clean.split("\\+", -1)
                : new String[] { clean };
        List<LanePart> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            LanePart part = LanePart.parse(rawPart);
            if (part == null) {
                return new LanePart[0];
            }
            parts.add(part);
        }
        return parts.toArray(new LanePart[0]);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static String customResourceName(String label) {
        String clean = label == null ? "" : label.trim();
        switch (clean) {
            case "Ls":
            case "Ls*":
            case "Rs":
            case "Rs*":
            case "S+L*":
            case "S*+L":
            case "S+R*":
            case "S*+R":
            case "S+Ls":
            case "S+Ls*":
            case "S*+Ls":
            case "S*+Ls*":
            case "S+Rs":
            case "S+Rs*":
            case "S*+Rs":
            case "S*+Rs*":
            case "Ls+L":
            case "Ls+L*":
            case "Ls*+L":
            case "Ls*+L*":
            case "Rs+R":
            case "Rs+R*":
            case "Rs*+R":
            case "Rs*+R*":
            case "L+Rs":
            case "L+Rs*":
            case "L*+Rs":
            case "L*+R":
            case "L+R*":
            case "Ls+R":
            case "Ls+R*":
            case "Ls*+R":
            case "S+L+R":
            case "S+L+R*":
            case "S+L*+R":
            case "S*+L+R":
                return "global_image_landcustom_hud_" + resourceKey(clean);
            case "RampR*":
                return "global_image_hud_sou70";
            case "RampL*":
                return "global_image_hud_sou71";
            default:
                return "";
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static String resourceKey(String label) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '+') {
                out.append('_');
            } else if (c == '*') {
                out.append("_star");
            } else if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
