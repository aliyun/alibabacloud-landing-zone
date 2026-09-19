package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentMemoryRefDO;
import com.aliyun.autowonder.agent.AgentMemoryRefDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

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
        assertEquals("Always create a git worktree before coding.", mem.get("mem_id_7"));
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

    @Test
    void stablePathsAndOrderSurviveReorderedRefsAndNewMemories() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        when(memoryDao.findById(anyLong())).thenAnswer(call -> memory(call.getArgument(0)));
        when(refDao.listByVersion(401L)).thenReturn(List.of(ref(9), ref(7)))
                .thenReturn(List.of(ref(7), ref(9)))
                .thenReturn(List.of(ref(9), ref(3), ref(7)));

        Map<String, String> first = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);
        Map<String, String> reordered = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);
        Map<String, String> added = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);

        assertEquals(List.of("mem_id_7", "mem_id_9"), new ArrayList<>(first.keySet()));
        assertEquals(new ArrayList<>(first.entrySet()), new ArrayList<>(reordered.entrySet()));
        first.forEach((path, content) -> assertEquals(content, added.get(path)));
    }

    @Test
    void duplicateRefsDoNotDuplicateContextOrConsumeTheFiftyMemoryBudget() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        when(memoryDao.findById(anyLong())).thenAnswer(call -> memory(call.getArgument(0)));
        List<AgentMemoryRefDO> refs = new ArrayList<>();
        for (long id = 1; id <= 51; id++) {
            refs.add(ref(id));
            refs.add(ref(id));
        }
        Collections.reverse(refs);
        when(refDao.listByVersion(401L)).thenReturn(refs);

        Map<String, String> result = PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L);

        assertEquals(50, result.size());
        assertTrue(result.containsKey("mem_id_50"));
        assertFalse(result.containsKey("mem_id_51"));
        verify(memoryDao, times(1)).findById(1L);
        verify(memoryDao, never()).findById(51L);
    }

    @Test
    void nullTenantAndForeignMemoryAreExcludedWithoutCrashing() {
        AgentMemoryRefDao refDao = mock(AgentMemoryRefDao.class);
        MemoryDao memoryDao = mock(MemoryDao.class);
        AgentMemoryRefDO unscoped = ref(3);
        unscoped.setTenantId(null);
        when(refDao.listByVersion(401L)).thenReturn(java.util.Arrays.asList(null, unscoped, ref(7), ref(9)));
        MemoryDO unscopedMemory = memory(7L);
        unscopedMemory.setTenantId(null);
        MemoryDO foreign = memory(9L);
        foreign.setTenantId(999L);
        when(memoryDao.findById(7L)).thenReturn(unscopedMemory);
        when(memoryDao.findById(9L)).thenReturn(foreign);

        assertTrue(PackageContextAssembler.buildMemoryMap(refDao, memoryDao, 10000L, 401L).isEmpty());
        verify(memoryDao, never()).findById(3L);
    }

    private AgentMemoryRefDO ref(long id) {
        AgentMemoryRefDO ref = new AgentMemoryRefDO();
        ref.setTenantId(10000L);
        ref.setMemoryId(id);
        return ref;
    }

    private MemoryDO memory(Long id) {
        MemoryDO memory = new MemoryDO();
        memory.setId(id);
        memory.setTenantId(10000L);
        memory.setStatus("ADOPTED");
        memory.setContentMd("Exact requirement " + id);
        return memory;
    }
}
