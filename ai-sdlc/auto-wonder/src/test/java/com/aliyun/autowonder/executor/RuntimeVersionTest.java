package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeVersionTest {

    @Test
    void ordersStableVersionsNumericallyNotLexically() {
        assertTrue(RuntimeVersion.isBehind("0.2.9", "0.2.152"), "9 < 152 must compare numerically");
        assertTrue(RuntimeVersion.isBehind("0.2.151", "0.2.152"));
        assertTrue(RuntimeVersion.isBehind("0.9.0", "1.0.0"));
        assertTrue(RuntimeVersion.isBehind("1.2.3", "1.10.0"));
        assertEquals(0, RuntimeVersion.compare("0.2.152", "0.2.152").orElseThrow());
        assertFalse(RuntimeVersion.isBehind("0.2.152", "0.2.152"), "equal versions must not be behind");
        assertFalse(RuntimeVersion.isBehind("0.2.153", "0.2.152"), "a newer version must not be behind");
    }

    @Test
    void toleratesLeadingVAndSurroundingWhitespace() {
        assertEquals(0, RuntimeVersion.compare(" v1.2.3 ", "1.2.3").orElseThrow());
        assertEquals(0, RuntimeVersion.compare("1.2.3", " V1.2.3 ").orElseThrow());
        assertTrue(RuntimeVersion.isBehind("v1.2.3", "v1.2.4"));
    }

    @Test
    void treatsUnparseableVersionsAsUnknown() {
        assertTrue(RuntimeVersion.compare(null, "1.2.3").isEmpty());
        assertTrue(RuntimeVersion.compare("1.2.3", null).isEmpty());
        assertTrue(RuntimeVersion.compare("", "1.2.3").isEmpty());
        assertTrue(RuntimeVersion.compare("1.2", "1.2.3").isEmpty(), "missing patch is not a stable version");
        assertTrue(RuntimeVersion.compare("1.2.3.4", "1.2.3").isEmpty());
        assertTrue(RuntimeVersion.compare("1.2.3-beta", "1.2.3").isEmpty(), "pre-release is out of scope");
        assertTrue(RuntimeVersion.compare("latest", "1.2.3").isEmpty());
        assertTrue(RuntimeVersion.compare("1.2.x", "1.2.3").isEmpty());
        assertTrue(RuntimeVersion.compare("1.-2.3", "1.2.3").isEmpty());
        assertFalse(RuntimeVersion.isBehind(null, "1.2.3"), "unknown must never be treated as behind");
        assertFalse(RuntimeVersion.isBehind("latest", "1.2.3"), "unknown must never be treated as behind");
    }

    @Test
    void refusesToOverflowOnAbsurdInput() {
        // 20 digits cannot be held as a positive long; the guard keeps comparison unknown instead of wrapping.
        assertTrue(RuntimeVersion.compare("99999999999999999999.0.0", "1.0.0").isEmpty());
        // 19 digits pass a length-only guard but still exceed Long.MAX_VALUE, so accumulation must be checked too.
        assertTrue(RuntimeVersion.compare("9999999999999999999.0.0", "1.0.0").isEmpty());
        assertTrue(RuntimeVersion.compare("1.0.0", "9999999999999999999.0.0").isEmpty());
    }
}
