package com.bydhud.app;

final class NavPermissionRuntimeProbe {
    private static final String[] ACCESSIBILITY_SECTION_LABELS = new String[]{
            "Bound services:",
            "Enabled services:",
            "Binding services:",
            "Crashed services:"
    };

    private NavPermissionRuntimeProbe() {
    }

    static Result parseAccessibilityDumpsys(String packageName, String dumpsys) {
        String normalized = packageName == null ? "" : packageName.trim();
        String service = normalized + "/" + normalized + ".NavAccessibilityService";
        String shortService = normalized + "/.NavAccessibilityService";
        String value = dumpsys == null ? "" : dumpsys;
        boolean enabled = sectionContains(value, "Enabled services:", service, shortService);
        boolean bound = sectionContains(value, "Bound services:", service, shortService);
        boolean crashed = sectionContains(value, "Crashed services:", service, shortService);
        return new Result(enabled, bound, crashed, value.trim());
    }

    private static boolean sectionContains(String value, String label,
            String service, String shortService) {
        int start = value.indexOf(label);
        if (start < 0) {
            return false;
        }
        int end = value.length();
        for (String candidate : ACCESSIBILITY_SECTION_LABELS) {
            if (candidate.equals(label)) {
                continue;
            }
            int next = value.indexOf(candidate, start + label.length());
            if (next >= 0 && next < end) {
                end = next;
            }
        }
        String section = value.substring(start, end);
        return section.contains(service) || section.contains(shortService);
    }

    static final class Result {
        final boolean accessibilityEnabledInDumpsys;
        final boolean accessibilityBoundInDumpsys;
        final boolean accessibilityCrashedInDumpsys;
        final String raw;

        Result(
                boolean accessibilityEnabledInDumpsys,
                boolean accessibilityBoundInDumpsys,
                boolean accessibilityCrashedInDumpsys,
                String raw) {
            this.accessibilityEnabledInDumpsys = accessibilityEnabledInDumpsys;
            this.accessibilityBoundInDumpsys = accessibilityBoundInDumpsys;
            this.accessibilityCrashedInDumpsys = accessibilityCrashedInDumpsys;
            this.raw = raw == null ? "" : raw;
        }

        boolean accessibilityRuntimeOk() {
            return accessibilityEnabledInDumpsys
                    && accessibilityBoundInDumpsys
                    && !accessibilityCrashedInDumpsys;
        }
    }
}
