package com.bydhud.mapcapture;

/** A Car App can stay resumed with a live presenter but no drawing Surface. */
public final class CarCapturePolicy {
    public static boolean mayAttach(boolean resumed, boolean hasRenderer, boolean surfaceValid, boolean hasPresenter) {
        return hasRenderer && !surfaceValid && (!resumed || hasPresenter);
    }
    static int[] size(int width, int height, int[] retained) {
        if (width > 0 && height > 0) return new int[]{width,height};
        return retained != null && retained[0] > 0 && retained[1] > 0 ? retained : null;
    }
}
