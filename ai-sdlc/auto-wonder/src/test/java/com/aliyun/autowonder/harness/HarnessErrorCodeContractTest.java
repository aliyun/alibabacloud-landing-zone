package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合并后错误码契约：新空间引导保留 40001–40003，初始化引导占用 40013–40015，全体码唯一。 */
class HarnessErrorCodeContractTest {

    @Test
    void keepsGuideCodesAndRenumbersOnboardingCodes() {
        assertEquals("40001", ErrorCode.WORKSPACE_GUIDE_NOT_ELIGIBLE.getCode());
        assertEquals("40002", ErrorCode.WORKSPACE_GUIDE_VERSION_CONFLICT.getCode());
        assertEquals("40003", ErrorCode.WORKSPACE_GUIDE_STATE_INVALID.getCode());
        assertEquals("40013", ErrorCode.HARNESS_TEMPLATE_UNAVAILABLE.getCode());
        assertEquals("40014", ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT.getCode());
        assertEquals("40015", ErrorCode.HARNESS_ONBOARDING_NOT_FOUND.getCode());
        assertEquals("40004", ErrorCode.HARNESS_SNAPSHOT_EXPIRED.getCode());
        assertEquals("40012", ErrorCode.HARNESS_SHARED_OBJECT_CONFLICT.getCode());
    }

    @Test
    void allErrorCodesAreUnique() {
        Set<String> codes = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            if (!codes.add(code.getCode())) {
                duplicates.add(code.getCode());
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate error codes: " + duplicates);
    }
}
