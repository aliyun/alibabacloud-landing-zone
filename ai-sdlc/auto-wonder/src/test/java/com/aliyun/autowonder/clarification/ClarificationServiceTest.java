package com.aliyun.autowonder.clarification;

import com.aliyun.autowonder.clarification.dto.ClarificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClarificationServiceTest {

    ClarificationDao dao;
    ClarificationService service;

    @BeforeEach
    void setUp() {
        dao = mock(ClarificationDao.class);
        service = new ClarificationService(dao);
    }

    @Test
    void getReturnsEmptyWhenAbsent() {
        when(dao.findByWorkitem(5L)).thenReturn(null);
        ClarificationVO vo = service.get(5L);
        assertEquals(5L, vo.getWorkitemId());
        assertNull(vo.getContentMd());
        assertEquals(0, vo.getVersion());
    }

    @Test
    void putInsertsWhenAbsent() {
        when(dao.findByWorkitem(5L)).thenReturn(null);
        ClarificationDO inserted = new ClarificationDO();
        inserted.setWorkitemId(5L);
        inserted.setContentMd("body");
        inserted.setVersion(0);
        when(dao.findByWorkitem(5L)).thenReturn(null, inserted);

        ClarificationVO vo = service.put(5L, "body", 100L, 7L);

        verify(dao).insert(argThat((ClarificationDO c) ->
                c.getWorkitemId() == 5L && c.getTenantId() == 100L
                        && "body".equals(c.getContentMd())));
        verify(dao, never()).update(anyLong(), anyLong(), any());
        assertEquals("body", vo.getContentMd());
        assertEquals(0, vo.getVersion());
    }

    @Test
    void putUpdatesAndIncrementsVersionWhenPresent() {
        ClarificationDO existing = new ClarificationDO();
        existing.setId(99L);
        existing.setWorkitemId(5L);
        existing.setTenantId(100L);
        existing.setContentMd("old");
        existing.setVersion(1);
        ClarificationDO updated = new ClarificationDO();
        updated.setId(99L);
        updated.setWorkitemId(5L);
        updated.setContentMd("new");
        updated.setVersion(2);
        when(dao.findByWorkitem(5L)).thenReturn(existing, updated);

        ClarificationVO vo = service.put(5L, "new", 100L, 7L);

        verify(dao).update(99L, 100L, "new");
        verify(dao, never()).insert(any());
        assertEquals("new", vo.getContentMd());
        assertEquals(2, vo.getVersion());
    }
}
