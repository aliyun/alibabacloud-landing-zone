package com.aliyun.autowonder.common.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseDOTest {
    static class SampleDO extends BaseDO {}

    @Test
    void exposesBaseFields() {
        SampleDO d = new SampleDO();
        d.setId(123L);
        d.setTenantId(456L);
        d.setVersion(2);
        d.setIsDeleted(0);
        assertEquals(123L, d.getId());
        assertEquals(456L, d.getTenantId());
        assertEquals(2, d.getVersion());
        assertEquals(0, d.getIsDeleted());
    }
}
