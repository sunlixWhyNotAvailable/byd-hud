package com.bydhud.app;

/** The selected diagnostic route applies to content and lifecycle writes alike. */
public final class HudCheckChannelPolicy {
    private HudCheckChannelPolicy() { }

    public static boolean supportsExtendedStage(HudCheckState state) {
        if (state == null || state.mode != HudCheckState.Mode.EXTENDED || state.isDenzaOem()) {
            return true;
        }
        return state.usesSomeIp() || state.isLivePipelineCheck()
                || (state.extendedIndex == 21 && state.usesInstrument());
    }

    static boolean changesWriter(HudCheckState before, HudCheckState after) {
        return before != null && after != null
                && (before.channel != after.channel
                || before.effectiveAddSomeIp() != after.effectiveAddSomeIp()
                || before.isDenzaOem() != after.isDenzaOem());
    }

    static boolean isolated(HudCheckState state) {
        return state != null && state.channel != HudCheckState.Channel.BASIC;
    }

    static boolean usesInstrument(HudCheckState state) {
        return state == null || state.usesInstrument();
    }

    static boolean usesAmap(HudCheckState state) {
        return state == null || state.usesAmap();
    }
}
