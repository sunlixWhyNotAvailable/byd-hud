package com.bydhud.mapcapture;

/** Keeps one full diagnostic per failure episode, with a summary when it changes or recovers. */
public final class RepeatedFailure {
    private String last = "";
    private long repeats;

    public synchronized String record(String detail) {
        if (detail.equals(last)) { repeats++; return null; }
        String summary = finish("changed");
        last = detail;
        return summary == null ? detail : summary + "\n" + detail;
    }

    public synchronized String recovered() { return finish("recovered"); }

    private String finish(String outcome) {
        String result = last.isEmpty() ? null : "failure_episode " + outcome + " repeats=" + repeats;
        last = "";
        repeats = 0L;
        return result;
    }
}
