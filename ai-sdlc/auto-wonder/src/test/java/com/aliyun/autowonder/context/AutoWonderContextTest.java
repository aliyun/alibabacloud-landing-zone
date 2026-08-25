package com.aliyun.autowonder.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoWonderContextTest {
    @AfterEach
    void tearDown() { AutoWonderContext.destroy(); }

    @Test
    void holdsUserAndWorkspace() {
        AutoWonderContext.get().setUserId(7L);
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        assertEquals(7L, AutoWonderContext.get().getUserId());
        assertEquals(9L, AutoWonderContext.get().getCurrentWorkspaceId());
    }

    @Test
    void clearedAfterDestroy() {
        AutoWonderContext.get().setUserId(7L);
        AutoWonderContext.destroy();
        assertNull(AutoWonderContext.get().getUserId());
    }
}
