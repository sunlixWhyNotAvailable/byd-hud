package com.bydhud.app;

//models one Waze lane cell so lane parsing can compare geometry output and active state consistently.

final class WazeLaneCell {
    final int index;
    final int x1;
    final int y1;
    final int x2;
    final int y2;
    final String geometryGuess;
    final String geometryToken;
    final String finalToken;
    final String source;
    final String failureReason;
    final int geometryCount;
    final long geometryNs;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    WazeLaneCell(int index, int x1, int y1, int x2, int y2,
            String geometryGuess, String failureReason) {
        this(index, x1, y1, x2, y2, geometryGuess, geometryGuess, "", failureReason, 0, 0L);
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    WazeLaneCell(int index, int x1, int y1, int x2, int y2,
            String geometryToken, String finalToken,
            String source, String failureReason) {
        this(index, x1, y1, x2, y2, geometryToken, finalToken,
                source, failureReason, 0, 0L);
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    WazeLaneCell(int index, int x1, int y1, int x2, int y2,
            String geometryToken, String finalToken,
            String source, String failureReason,
            int geometryCount, long geometryNs) {
        this.index = Math.max(0, index);
        this.x1 = Math.max(0, x1);
        this.y1 = Math.max(0, y1);
        this.x2 = Math.max(this.x1, x2);
        this.y2 = Math.max(this.y1, y2);
        this.geometryToken = geometryToken == null ? "" : geometryToken;
        this.finalToken = finalToken == null ? "" : finalToken;
        this.source = source == null ? "" : source;
        this.geometryGuess = this.finalToken;
        this.failureReason = failureReason == null ? "" : failureReason;
        this.geometryCount = Math.max(0, geometryCount);
        this.geometryNs = Math.max(0L, geometryNs);
    }
}
