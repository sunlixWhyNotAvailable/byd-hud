package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FidCatalogTest {
    @Test
    public void recursivelyEnumeratesOnlyStaticFinalIntAndLongFields() throws Exception {
        FidCatalog.Result result = FidCatalog.collect(new String[]{FakeRoot.class.getName()});

        assertEquals(1, result.loadedRoots);
        assertEquals(2, result.entries.size());
        String text = FidCatalog.text(result);
        assertTrue(text.contains("FakeRoot.INT_VALUE\tint\t7"));
        assertTrue(text.contains("FakeRoot$Nested.LONG_VALUE\tlong\t9"));
        assertFalse(text.contains("IGNORED"));
        String json = FidCatalog.json(result);
        assertEquals(json, FidCatalog.json(result));
        assertTrue(json.contains("\"entryCount\": 2"));
    }

    @Test
    public void absentFrameworkClassesProduceUnavailableResult() {
        FidCatalog.Result result = FidCatalog.collect(new String[]{
                "android.hardware.bydauto.DoesNotExist"
        });

        assertFalse(result.available());
        assertEquals(0, result.loadedRoots);
        assertTrue(FidCatalog.text(result).startsWith("status=unavailable\n"));
    }

    private static final class FakeRoot {
        static final int INT_VALUE = 7;
        static int IGNORED_MUTABLE = 8;
        static final String IGNORED_TEXT = "ignored";

        private static final class Nested {
            static final long LONG_VALUE = 9L;
        }
    }
}
