package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public final class HudTextTransliteratorTest {
    @Test
    public void offPreservesOriginalTextExactly() {
        String text = "  Київ — A2  ";
        assertEquals(text, HudTextTransliterator.transform(text, HudTextTransliterator.OFF));
    }

    @Test
    public void ukrainianUsesResolution55MappingsAndContext() {
        assertEquals(
                "Yenakiieve Zghurskyi Yizhakevych Stryi Koriukivka "
                        + "Kostiantyn Znamianka",
                HudTextTransliterator.transform(
                        "Єнакієве Згурський Їжакевич Стрий Корюківка "
                                + "Костянтин Знам'янка",
                        HudTextTransliterator.UKRAINIAN));
    }

    @Test
    public void ukrainianOmitsInWordSoftSignAndApostrophe() {
        assertEquals("kin sil Sloviansk", HudTextTransliterator.transform(
                "кінь сіль Слов'янськ", HudTextTransliterator.UKRAINIAN));
        assertEquals("slovo'", HudTextTransliterator.transform(
                "слово'", HudTextTransliterator.UKRAINIAN));
    }

    @Test
    public void ukrainianPreservesTitleAndAllCapsCase() {
        assertEquals("Khreshchatyk SHCHORSKYI", HudTextTransliterator.transform(
                "Хрещатик ЩОРСЬКИЙ", HudTextTransliterator.UKRAINIAN));
    }

    @Test
    public void ukrainianPreservesPunctuationDigitsLatinAndUnsupportedCyrillic() {
        assertEquals("A-12, Kyiv! Ёzh ъ", HudTextTransliterator.transform(
                "A-12, Київ! Ёж ъ", HudTextTransliterator.UKRAINIAN));
    }

    @Test
    public void directFrameTransformsOnlyNavigationText() {
        DirectTbtFrame source = new DirectTbtFrame(
                4, 6, 7, 120, "Київ", "Поверніть праворуч", "raw-display",
                new byte[] {1}, new byte[] {2}, null,
                DirectTbtFrame.AlertOverlay.inactive());

        DirectTbtFrame transformed = HudTextTransliterator.transformFrame(
                source, HudTextTransliterator.UKRAINIAN);

        assertEquals("Kyiv", transformed.getRoadText());
        assertEquals("Povernit pravoruch", transformed.getCueText());
        assertEquals("raw-display", transformed.getDisplayText());
        assertEquals(120, transformed.getDistanceMeters());
        assertEquals(4, transformed.getRawManeuverType());
        assertEquals("Kyiv", VehicleTbtPublisher.roadTextForTest(transformed));
        assertEquals("Kyiv", DirectTbtPayload.prepare(
                transformed, DirectTbtPayload.Options.ALL).displayText());
    }

    @Test
    public void offFrameProjectionReturnsSameImmutableFrame() {
        DirectTbtFrame source = DirectTbtFrame.empty();
        assertSame(source, HudTextTransliterator.transformFrame(
                source, HudTextTransliterator.OFF));
    }
}
