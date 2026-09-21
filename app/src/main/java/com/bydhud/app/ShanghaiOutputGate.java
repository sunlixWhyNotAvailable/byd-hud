package com.bydhud.app;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Excludes our writes while the stock navigator owns the Shanghai control. */
final class ShanghaiOutputGate {
    private static final ReentrantReadWriteLock GATE = new ReentrantReadWriteLock(true);
    private static volatile boolean suspended;

    private ShanghaiOutputGate() { }

    static boolean isSuspended() { return suspended; }

    static void suspend() throws IOException, InterruptedException {
        // Do not begin mock injection until already dispatched writes have returned.
        if (!GATE.writeLock().tryLock(5, TimeUnit.SECONDS)) {
            throw new IOException("HUD output did not become idle");
        }
        try { suspended = true; }
        finally { GATE.writeLock().unlock(); }
    }

    static void resume() {
        GATE.writeLock().lock();
        try { suspended = false; }
        finally { GATE.writeLock().unlock(); }
    }

    static boolean enterWrite() {
        GATE.readLock().lock();
        if (!suspended) return true;
        GATE.readLock().unlock();
        return false;
    }

    static void leaveWrite() { GATE.readLock().unlock(); }
}
