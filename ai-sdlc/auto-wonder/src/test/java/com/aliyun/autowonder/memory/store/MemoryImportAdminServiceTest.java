package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryImportAdminServiceTest {
    private MemoryImportDao dao;
    private MemoryImportAdminService service;

    @BeforeEach
    void setUp() {
        dao = mock(MemoryImportDao.class);
        service = new MemoryImportAdminService(dao);
    }

    @Test
    void listsTenantScopedSourcesAndReceipts() {
        MemoryImportSourceDO source = source(11L, "ACTIVE", 3);
        MemoryImportReceiptDO receipt = new MemoryImportReceiptDO();
        when(dao.listSources(7L)).thenReturn(List.of(source));
        when(dao.listReceipts(7L, 11L, 100)).thenReturn(List.of(receipt));

        assertEquals(List.of(source), service.listSources(7L));
        assertEquals(List.of(receipt), service.listReceipts(7L, 11L));
    }

    @Test
    void supportsPauseRetireResumeAndExplicitRecuration() {
        MemoryImportSourceDO source = source(11L, "ACTIVE", 3);
        when(dao.findSourceById(7L, 11L)).thenReturn(source);
        when(dao.updateSourceStatus(anyLong(), anyLong(), anyString(), anyInt())).thenReturn(1);

        service.changeStatus(7L, 11L, "PAUSED", 3);
        service.changeStatus(7L, 11L, "RETIRED", 3);
        service.changeStatus(7L, 11L, "ACTIVE", 3);
        service.changeStatus(7L, 11L, "RECURATE_REQUESTED", 3);

        verify(dao).updateSourceStatus(7L, 11L, "PAUSED", 3);
        verify(dao).updateSourceStatus(7L, 11L, "RETIRED", 3);
        verify(dao).updateSourceStatus(7L, 11L, "ACTIVE", 3);
        verify(dao).updateSourceStatus(7L, 11L, "RECURATE_REQUESTED", 3);
        assertThrows(IllegalArgumentException.class,
                () -> service.changeStatus(7L, 11L, "OBSERVED", 3));
    }

    @Test
    void reportsOptimisticConflictAndMissingSource() {
        assertThrows(IllegalArgumentException.class,
                () -> service.changeStatus(7L, 99L, "PAUSED", 1));
        when(dao.findSourceById(7L, 11L)).thenReturn(source(11L, "ACTIVE", 3));
        when(dao.updateSourceStatus(7L, 11L, "PAUSED", 3)).thenReturn(0);
        assertThrows(MemoryDocumentService.MemoryConflictException.class,
                () -> service.changeStatus(7L, 11L, "PAUSED", 3));
    }

    private MemoryImportSourceDO source(long id, String status, int version) {
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setId(id);
        source.setTenantId(7L);
        source.setStatus(status);
        source.setVersion(version);
        return source;
    }
}
