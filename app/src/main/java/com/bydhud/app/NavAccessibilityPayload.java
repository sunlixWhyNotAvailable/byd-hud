package com.bydhud.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NavAccessibilityPayload {
    private NavAccessibilityPayload() {
    }

    static List<Node> nodes(String payload) {
        String cleanPayload = NavTextNormalizer.cleanText(payload);
        if (cleanPayload.isEmpty()) {
            return Collections.emptyList();
        }
        List<Node> nodes = new ArrayList<>();
        String[] segments = cleanPayload.split(";");
        for (String rawSegment : segments) {
            String segment = NavTextNormalizer.cleanText(rawSegment);
            if (!segment.startsWith("node[")) {
                continue;
            }
            nodes.add(new Node(
                    fieldValue(segment, " id="),
                    fieldValue(segment, " text="),
                    fieldValue(segment, " desc="),
                    fieldValue(segment, " class="),
                    fieldValue(segment, " bounds=")));
        }
        return nodes;
    }

    static String fieldValue(String segment, String marker) {
        int start = segment.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = segment.length();
        String[] markers = {" id=", " text=", " desc=", " class=", " bounds="};
        for (String nextMarker : markers) {
            int next = segment.indexOf(nextMarker, start);
            if (next >= 0 && next < end) {
                end = next;
            }
        }
        return NavTextNormalizer.cleanText(segment.substring(start, end));
    }

    static final class Node {
        final String id;
        final String text;
        final String desc;
        final String className;
        final String bounds;

        Node(String id, String text, String desc, String className, String bounds) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
            this.desc = desc == null ? "" : desc;
            this.className = className == null ? "" : className;
            this.bounds = bounds == null ? "" : bounds;
        }

        boolean idEndsWith(String suffix) {
            return NavTextNormalizer.lower(id).endsWith(NavTextNormalizer.lower(suffix));
        }
    }
}
