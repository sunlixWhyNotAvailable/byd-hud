package com.bydhud.app;

//keeps diagnostic projection mode ids stable across UI, prefs, and service code.
enum DashboardProjectionMode {
    TEXTURE_VIEW("texture_view"),
    SURFACE_VIEW("surface_view");

    final String id;

    DashboardProjectionMode(String id) {
        this.id = id;
    }

    static DashboardProjectionMode fromId(String id) {
        for (DashboardProjectionMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return TEXTURE_VIEW;
    }
}
