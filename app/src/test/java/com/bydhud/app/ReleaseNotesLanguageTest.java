package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReleaseNotesLanguageTest {
    private static final String BODY =
            "<!-- bydhud:release-notes:en -->\n"
                    + "## English\n\n- fixed\n"
                    + "<!-- /bydhud:release-notes:en -->\n\n"
                    + "<!-- bydhud:release-notes:uk -->\n"
                    + "## Українська\n\n- виправлено\n"
                    + "<!-- /bydhud:release-notes:uk -->\n\n"
                    + "<!-- bydhud:release-notes:ru -->\n"
                    + "## Русский\n\n- исправлено\n"
                    + "<!-- /bydhud:release-notes:ru -->\n\n"
                    + "SHA-256: ABC123";

    @Test
    public void selectsCurrentAppLanguageWithoutSharedFooter() {
        assertEquals("## English\n\n- fixed",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(BODY, "en"));
        assertEquals("## Українська\n\n- виправлено",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(BODY, "uk"));
        assertEquals("## Русский\n\n- исправлено",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(BODY, "ru"));
    }

    @Test
    public void missingOrEmptyUkrainianBlockFallsBackToEnglish() {
        String missing = BODY.replace(
                "<!-- bydhud:release-notes:uk -->\n## Українська\n\n- виправлено\n"
                        + "<!-- /bydhud:release-notes:uk -->\n\n",
                "");
        String empty = BODY.replace("## Українська\n\n- виправлено", "   ");

        assertEquals("## English\n\n- fixed",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(missing, true));
        assertEquals("## English\n\n- fixed",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(empty, true));
    }

    @Test
    public void missingEmptyOrMalformedRussianBlockFallsBackToEnglish() {
        String complete = "<!-- bydhud:release-notes:en -->\nEnglish\n"
                + "<!-- /bydhud:release-notes:en -->\n"
                + "<!-- bydhud:release-notes:ru -->\nРусский\n"
                + "<!-- /bydhud:release-notes:ru -->";
        String missing = complete.replace(
                "\n<!-- bydhud:release-notes:ru -->\nРусский\n<!-- /bydhud:release-notes:ru -->", "");
        String empty = complete.replace("\nРусский\n", "\n   \n");
        String malformed = complete.replace("<!-- /bydhud:release-notes:ru -->", "");

        assertEquals("English", AppUpdateManager.INSTANCE.releaseNotesForLanguage(missing, "ru"));
        assertEquals("English", AppUpdateManager.INSTANCE.releaseNotesForLanguage(empty, "ru"));
        assertEquals("English", AppUpdateManager.INSTANCE.releaseNotesForLanguage(malformed, "ru"));
    }

    @Test
    public void malformedRequestedBlockFallsBackToEnglish() {
        String malformed = BODY.replace("<!-- /bydhud:release-notes:uk -->", "");

        assertEquals("## English\n\n- fixed",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(malformed, true));
    }

    @Test
    public void duplicateOrCrossedMarkersAreRejected() {
        String duplicateUkOpen = BODY.replace(
                "<!-- bydhud:release-notes:uk -->\n",
                "<!-- bydhud:release-notes:uk -->\n<!-- bydhud:release-notes:uk -->\n");
        String crossed = "<!-- bydhud:release-notes:en -->\n"
                + "English\n"
                + "<!-- bydhud:release-notes:uk -->\n"
                + "Українська\n"
                + "<!-- /bydhud:release-notes:en -->\n"
                + "<!-- /bydhud:release-notes:uk -->";

        assertEquals("## English\n\n- fixed",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(duplicateUkOpen, true));
        assertEquals(crossed,
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(crossed, true));
    }

    @Test
    public void duplicateOrCrossedRussianMarkersAreRejected() {
        String duplicate = BODY.replace(
                "<!-- bydhud:release-notes:ru -->\n",
                "<!-- bydhud:release-notes:ru -->\n<!-- bydhud:release-notes:ru -->\n");
        String crossed = "<!-- bydhud:release-notes:en -->\nEnglish\n"
                + "<!-- bydhud:release-notes:ru -->\nРусский\n"
                + "<!-- /bydhud:release-notes:en -->\n"
                + "<!-- /bydhud:release-notes:ru -->";

        assertEquals("## English\n\n- fixed",
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(duplicate, "ru"));
        assertEquals(crossed,
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(crossed, "ru"));
    }

    @Test
    public void markerMustOccupyItsOwnExactLine() {
        String nearMiss = "prefix <!-- bydhud:release-notes:en -->\nEnglish\n"
                + "<!-- /bydhud:release-notes:en --> suffix";

        assertEquals(nearMiss,
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(nearMiss, false));
    }

    @Test
    public void englishRequestWithOnlyUkrainianBlockUsesLegacyBody() {
        String ukrainianOnly = "<!-- bydhud:release-notes:uk -->\n"
                + "Українська\n"
                + "<!-- /bydhud:release-notes:uk -->";

        assertEquals(ukrainianOnly,
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(ukrainianOnly, false));
    }

    @Test
    public void legacyBodyIsReturnedUnchanged() {
        String legacy = "## Changes\r\n\r\n- legacy\r\nSHA-256: OLD";

        assertEquals(legacy,
                AppUpdateManager.INSTANCE.releaseNotesForLanguage(legacy, true));
    }
}
