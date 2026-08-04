package com.aliyun.autowonder.common.error;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BizExceptionTest {
    @Test
    void carriesCodeAndMessage() {
        BizException ex = new BizException(ErrorCode.NO_PERMISSION);
        assertEquals("10403", ex.getCode());
        assertEquals(ErrorCode.NO_PERMISSION.getMessage(), ex.getMessage());
    }

    @Test
    void allowsCustomMessage() {
        BizException ex = new BizException(ErrorCode.CONFLICT, "用户名已存在");
        assertEquals("10409", ex.getCode());
        assertEquals("用户名已存在", ex.getMessage());
    }
}
