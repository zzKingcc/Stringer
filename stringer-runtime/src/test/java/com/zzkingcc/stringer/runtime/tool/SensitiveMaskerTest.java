package com.zzkingcc.stringer.runtime.tool;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 敏感参数掩码：只改"给人看的"参数文本，且要盖得住嵌套。
 */
class SensitiveMaskerTest {

    @Test
    void masksDeclaredNamesIncludingNestedOnes() {
        String masked = SensitiveMasker.mask(
                "{\"orderNo\":\"FR1\",\"phone\":\"13800000000\",\"contact\":{\"email\":\"a@b.c\"}}",
                Set.of("phone", "email"));

        assertEquals("{\"orderNo\":\"FR1\",\"phone\":\"***\",\"contact\":{\"email\":\"***\"}}", masked);
    }

    @Test
    void masksWholeValueWhateverItsShape() {
        String masked = SensitiveMasker.mask(
                "{\"idCard\":{\"no\":\"123\"},\"phones\":[\"1\",\"2\"]}",
                Set.of("idCard", "phones"));

        assertEquals("{\"idCard\":\"***\",\"phones\":\"***\"}", masked);
    }

    @Test
    void returnsInputUntouchedWhenNothingToDo() {
        String args = "{\"orderNo\":\"FR1\"}";

        assertEquals(args, SensitiveMasker.mask(args, Set.of()));
        assertEquals(args, SensitiveMasker.mask(args, null));
        assertEquals("", SensitiveMasker.mask("", Set.of("phone")));
        assertNull(SensitiveMasker.mask(null, Set.of("phone")));
    }

    @Test
    void nonJsonArgumentsAreReturnedAsIs() {
        // 参数不是合法 JSON 时，不能因为"要脱敏"反而把原文弄丢
        assertEquals("not-json", SensitiveMasker.mask("not-json", Set.of("phone")));
    }
}
