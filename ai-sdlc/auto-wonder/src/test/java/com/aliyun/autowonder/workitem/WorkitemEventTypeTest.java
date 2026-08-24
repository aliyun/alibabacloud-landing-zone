package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemEventTypeTest {

    @Test
    void keepsPersistedCodesAndPresentationLabelsCentralized() {
        assertEquals("EXTERNAL_BUSINESS_OWNER_CHANGE", WorkitemEventType.EXTERNAL_BUSINESS_OWNER_CHANGE.code());
        assertEquals("外部业务负责人已变更", WorkitemEventType.EXTERNAL_BUSINESS_OWNER_CHANGE.actionLabel());
        assertTrue(WorkitemEventType.fromCode("AONE_UPDATE").isPresent());
    }
}
