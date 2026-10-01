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
    public static void main(String[] args) {
        check(mayAttach(true,true,false,true), "resumed host lost Surface (field regression)");
        check(!mayAttach(true,true,true,true), "visible host keeps its target");
        check(mayAttach(false,true,false,false), "paused presenter may be recreated");
        check(!mayAttach(true,true,false,false), "resuming host has not created presenter yet");
        check(!mayAttach(false,false,false,false), "disposed screen must not be revived");
        check(size(0,0,null) == null, "missing size waits, without inventing a viewport");
        int[] last = {1920,1080};
        check(size(0,0,last) == last, "destroyed surface uses this holder's last actual size");
        check(size(1280,720,last)[0] == 1280, "new valid size supersedes retained size");
        System.out.println("Car capture: resumed Surface loss, visible host, pause, initialization and disposal PASS");
    }
    private static void check(boolean value, String scenario) { if (!value) throw new AssertionError(scenario); }
}
