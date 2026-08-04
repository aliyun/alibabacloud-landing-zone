package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentMemoryRefDO;
import com.aliyun.autowonder.agent.AgentMemoryRefDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackageContextMemoryTest {

    @Test
    void buildMemoryMap_loadsAdoptedMemoryFromRefs() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        AgentMemoryRefDO ref = new AgentMemoryRefDO();
        ref.setTenantId(10000L);
        ref.setAgentVersionId(401L);
        ref.setMemoryId(7L);
        when(refDao.listByVersion(401L)).thenReturn(List.of(ref));
        MemoryDO m = new MemoryDO();
        m.setId(7L);
        m.setTenantId(10000L);
        m.setStatus("ADOPTED");
        m.setTitle("prefer worktree branches");
        m.setContentMd("Always create a git worktree before coding.");
        when(memoryDao.findById(7L)).thenReturn(m);

        Map<String, String> mem = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);

        assertEquals(1, mem.size());
        assertTrue(mem.values().iterator().next().contains("worktree"));
    }

    @Test
    void buildMemoryMapSkipsReferencedMemoryThatIsNotAdopted() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        AgentMemoryRefDO ref = new AgentMemoryRefDO();
        ref.setTenantId(10000L);
        ref.setMemoryId(7L);
        when(refDao.listByVersion(401L)).thenReturn(List.of(ref));
        MemoryDO pending = new MemoryDO();
        pending.setId(7L);
        pending.setTenantId(10000L);
        pending.setStatus("PENDING");
        pending.setContentMd("not reviewed");
        when(memoryDao.findById(7L)).thenReturn(pending);

        Map<String, String> mem = PackageContextAssembler.buildMemoryMap(
                refDao, memoryDao, 10000L, 401L);

        assertTrue(mem.isEmpty());
    }

    @Test
    void buildMemoryMap_skipsCrossTenantRefBlankContentAndMissing() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        AgentMemoryRefDO blankRef = new AgentMemoryRefDO();
        blankRef.setTenantId(10000L);
        blankRef.setMemoryId(7L);
        AgentMemoryRefDO crossTenantRef = new AgentMemoryRefDO();
        crossTenantRef.setTenantId(999L);
        crossTenantRef.setMemoryId(8L);
        AgentMemoryRefDO missingRef = new AgentMemoryRefDO();
        missingRef.setTenantId(10000L);
        missingRef.setMemoryId(9L);
        when(refDao.listByVersion(401L)).thenReturn(List.of(blankRef, crossTenantRef, missingRef));
        MemoryDO blank = new MemoryDO();
        blank.setId(7L);
        blank.setTenantId(10000L);
        blank.setContentMd("   ");
        when(memoryDao.findById(7L)).thenReturn(blank);
        when(memoryDao.findById(9L)).thenReturn(null);

        Map<String, String> mem = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);

        assertTrue(mem.isEmpty());
        verify(memoryDao, never()).findById(8L);
    }

    @Test
    void buildMemoryMap_nullRefsYieldsEmpty() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        when(refDao.listByVersion(401L)).thenReturn(null);

        Map<String, String> mem = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);

        assertTrue(mem.isEmpty());
        verifyNoInteractions(memoryDao);
    }
}
