package com.bydhud.app;

import java.io.IOException;
import java.util.Locale;

/** Applies the signer-origin policy before an APK can enter the patch pipeline. */
final class NavigatorPatchTrustPolicy {
    static final String WAZE_STOCK_SIGNER =
            "03637F6C5D8F604E6FDB79A6FFBFA578DE4E318F8DA22FC6106665247F8807D7";
    static final String WAZE_PROJECT_SIGNER =
            "7A75DDB02E03638A7CBC2429891A73A5E42119B3E98D5A9D789E61E1851EC3E4";
    static final String GMAPS_REVANCED_SOURCE_SIGNER =
            "297AB32BB4B6FD58E85F064E2A333FCC75CB3D89D63C1118D24547619EC8E3E4";
    static final String GMAPS_MORPHE_SOURCE_SIGNER =
            "CC6FE764C6CF68A6F383A8104FE667161C7F53D9DCB93A7B46FEEB4377C333B6";
    static final String GMAPS_MORPHE_VERSION_NAME = "26.30.09.950492155";
    static final long GMAPS_MORPHE_VERSION_CODE = 1068694917L;
    static final String GMAPS_MORPHE_SET_FINGERPRINT =
            "BB023481F24D01C43DE25B56BE75B9FB4C4D91A84DAF0377CF6504E5C58C6F3E";
    static final String GMAPS_PROJECT_SIGNER =
            "DF66EB974C0829B4A44A931A10FA6EA86BC702602C5C14F470E7FE385E47F0F7";

    enum Origin {
        WAZE_STOCK,
        WAZE_PROJECT,
        GMAPS_REVANCED_SOURCE,
        GMAPS_MORPHE_SOURCE,
        GMAPS_PROJECT,
        DEVICE_LOCAL
    }

    static final class Decision {
        final boolean accepted;
        final String code;
        final Origin origin;

        private Decision(boolean accepted, String code, Origin origin) {
            this.accepted = accepted;
            this.code = code;
            this.origin = origin;
        }

        static Decision accepted(Origin origin) {
            return new Decision(true, "TRUST_ACCEPTED", origin);
        }

        static Decision rejected(String code) {
            return new Decision(false, code, null);
        }
    }

    static final class TrustException extends IOException {
        final String code;

        TrustException(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
        }
    }

    private NavigatorPatchTrustPolicy() {
    }

    static void require(NavigatorPatchStore.Profile profile, String signerSha256,
            boolean mandatoryPatchMarkerPresent, boolean localSignerMatches)
            throws TrustException {
        require(profile, signerSha256, "", -1L, "",
                mandatoryPatchMarkerPresent, localSignerMatches);
    }

    static void require(NavigatorPatchStore.Profile profile, String signerSha256,
            String versionName, long versionCode, String setFingerprint,
            boolean mandatoryPatchMarkerPresent, boolean localSignerMatches)
            throws TrustException {
        Decision decision = evaluate(profile, signerSha256, versionName, versionCode,
                setFingerprint, mandatoryPatchMarkerPresent, localSignerMatches);
        if (!decision.accepted) {
            throw new TrustException(decision.code,
                    detail(profile, signerSha256, versionName, versionCode,
                            setFingerprint, mandatoryPatchMarkerPresent));
        }
    }

    static Decision evaluate(NavigatorPatchStore.Profile profile, String signerSha256,
            boolean mandatoryPatchMarkerPresent, boolean localSignerMatches) {
        return evaluate(profile, signerSha256, "", -1L, "",
                mandatoryPatchMarkerPresent, localSignerMatches);
    }

    static Decision evaluate(NavigatorPatchStore.Profile profile, String signerSha256,
            String versionName, long versionCode, String setFingerprint,
            boolean mandatoryPatchMarkerPresent, boolean localSignerMatches) {
        if (profile == null) return Decision.rejected("TRUST_PROFILE_REQUIRED");
        String signer = normalize(signerSha256);
        if (signer.isEmpty()) return Decision.rejected("TRUST_SIGNER_MISSING");
        if (profile == NavigatorPatchStore.Profile.WAZE) {
            if (WAZE_STOCK_SIGNER.equals(signer)) {
                return Decision.accepted(Origin.WAZE_STOCK);
            }
            if (WAZE_PROJECT_SIGNER.equals(signer)) {
                return Decision.accepted(Origin.WAZE_PROJECT);
            }
        } else if (profile == NavigatorPatchStore.Profile.GMAPS) {
            if (GMAPS_REVANCED_SOURCE_SIGNER.equals(signer)) {
                return Decision.accepted(Origin.GMAPS_REVANCED_SOURCE);
            }
            if (GMAPS_MORPHE_SOURCE_SIGNER.equals(signer)) {
                return GMAPS_MORPHE_VERSION_NAME.equals(versionName)
                        && GMAPS_MORPHE_VERSION_CODE == versionCode
                        && GMAPS_MORPHE_SET_FINGERPRINT.equals(normalize(setFingerprint))
                        ? Decision.accepted(Origin.GMAPS_MORPHE_SOURCE)
                        : Decision.rejected("TRUST_GMAPS_MORPHE_ARTIFACT_MISMATCH");
            }
            if (GMAPS_PROJECT_SIGNER.equals(signer)) {
                return Decision.accepted(Origin.GMAPS_PROJECT);
            }
        }
        if (localSignerMatches) {
            return mandatoryPatchMarkerPresent
                    ? Decision.accepted(Origin.DEVICE_LOCAL)
                    : Decision.rejected("TRUST_LOCAL_SIGNER_UNPATCHED");
        }
        return Decision.rejected("TRUST_UNKNOWN_SIGNER");
    }

    static Decision evaluateWazeLifecycleV2(String signerSha256,
            boolean canonicalProjectAssetMatches, boolean localSignerMatches,
            boolean localLifecyclePatchVerified) {
        String signer = normalize(signerSha256);
        if (signer.isEmpty()) return Decision.rejected("TRUST_SIGNER_MISSING");
        if (WAZE_PROJECT_SIGNER.equals(signer)) {
            return canonicalProjectAssetMatches
                    ? Decision.accepted(Origin.WAZE_PROJECT)
                    : Decision.rejected("TRUST_WAZE_PROJECT_ASSET_MISMATCH");
        }
        if (WAZE_STOCK_SIGNER.equals(signer)) {
            return Decision.rejected("TRUST_WAZE_STOCK_LIFECYCLE_V2_UNAVAILABLE");
        }
        if (localSignerMatches) {
            return localLifecyclePatchVerified
                    ? Decision.accepted(Origin.DEVICE_LOCAL)
                    : Decision.rejected("TRUST_LOCAL_LIFECYCLE_V2_UNVERIFIED");
        }
        return Decision.rejected("TRUST_UNKNOWN_SIGNER");
    }

    private static String detail(NavigatorPatchStore.Profile profile, String signer,
            String versionName, long versionCode, String setFingerprint,
            boolean mandatoryPatchMarkerPresent) {
        return "profile=" + (profile == null ? "" : profile.id)
                + ", signer=" + normalize(signer)
                + ", versionName=" + (versionName == null ? "" : versionName)
                + ", versionCode=" + versionCode
                + ", setFingerprint=" + normalize(setFingerprint)
                + ", mandatoryPatchMarker=" + mandatoryPatchMarkerPresent;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
