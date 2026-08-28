package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StockMapProtocol30011Test {
    @Test
    public void acceptsTbtOperationTwoAndExistingLayoutOperations() {
        assertTrue(StockMapProtocol30011.isSupportedOperation(2));
        assertTrue(StockMapProtocol30011.isSupportedOperation(3));
        assertTrue(StockMapProtocol30011.isSupportedOperation(4));
    }

    @Test
    public void rejectsInvalidOperationValues() {
        assertFalse(StockMapProtocol30011.isSupportedOperation(0));
        assertFalse(StockMapProtocol30011.isSupportedOperation(5));
    }

    @Test
    public void staleDispatchIsCancelledBeforeBinding() {
        assertTrue(StockMapProtocol30011.dispatch(null, 2, () -> false)
                .contains("cancelled stale"));
    }
}
