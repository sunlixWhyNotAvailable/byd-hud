package com.bydhud.app;

/**
 * Signer fingerprints for catalog provenance and Android update-continuity checks.
 *
 * <p>These values must never be used as navigator patch or runtime eligibility. Source APKs are
 * accepted by package/topology/DEX compatibility, while Android itself enforces signer continuity
 * for in-place updates.</p>
 */
final class NavigatorAssetSignerCatalog {
    static final String WAZE_STOCK_SIGNER =
            "03637F6C5D8F604E6FDB79A6FFBFA578DE4E318F8DA22FC6106665247F8807D7";
    static final String WAZE_PROJECT_SIGNER =
            "7A75DDB02E03638A7CBC2429891A73A5E42119B3E98D5A9D789E61E1851EC3E4";
    static final String GMAPS_PROJECT_SIGNER =
            "DF66EB974C0829B4A44A931A10FA6EA86BC702602C5C14F470E7FE385E47F0F7";

    private NavigatorAssetSignerCatalog() {
    }
}
