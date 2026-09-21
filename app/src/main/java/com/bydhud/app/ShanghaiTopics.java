package com.bydhud.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fixed read-only stock-navigation topics captured by the Shanghai diagnostic session. */
final class ShanghaiTopics {
    static final List<Long> ALL = Collections.unmodifiableList(Arrays.asList(
            0x0004010A00018001L, 0x0004010A00018002L, 0x0004010A00018003L,
            0x0004000700078001L, 0x0004000700078002L, 0x0004000700078003L,
            0x0004000700078004L, 0x0004000700078005L, 0x0004000700078006L,
            0x0004002B002B8001L, 0x0004002B002B8002L, 0x0004002D002D8001L,
            0x0004820282028001L, 0x0004820282028002L, 0x0004820282028003L,
            0x0004820282028004L, 0x0004820282028005L, 0x0004820282028006L,
            0x0004820282028007L, 0x0004820282028008L, 0x0004820282028009L,
            0x000482028202800AL, 0x000482028202800BL, 0x000482028202800CL,
            0x000482028202800DL, 0x000482028202800EL));

    private ShanghaiTopics() { }

    static String hex(long topic) {
        return String.format(java.util.Locale.ROOT, "0x%016X", topic);
    }
}
