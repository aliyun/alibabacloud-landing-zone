package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.squad.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryDirectoryServiceTest {
    @Test void editingNestedLegacyTypePreservesItsMeaning() {
        var document = new MemoryDocumentDO(); document.setPath("legacy_type.md");
        document.setContentMd("---\nname: Original\ndescription: When relevant\nmetadata:\n  type: feedback\n  source: historical\n---\nOriginal body");
        MemoryDirectoryService.describe(document);
        assertEquals("feedback", document.getMemoryType());
        String edited = MemoryTopicFormat.render("legacy_type", document.getMemoryType(), document.getTitle(),
                document.getDescription(), "Edited body", MemoryTopicFormat.parse(document.getContentMd()).metadata());
        var parsed = MemoryTopicFormat.parse(edited);
        assertEquals("feedback", parsed.metadata().get("type"));
        assertEquals("feedback", ((java.util.Map<?, ?>) parsed.metadata().get("metadata")).get("type"));
        assertEquals("historical", ((java.util.Map<?, ?>) parsed.metadata().get("metadata")).get("source"));
        document.setContentMd("---\ntype: user\nmetadata:\n  type: feedback\n---\nbody");
        MemoryDirectoryService.describe(document);
        assertEquals("user", document.getMemoryType(), "top-level type retains precedence");
    }

    @Test void listsNamedOwnersBeforeFirstDispatchWithoutCreatingStores() {
        var agents = mock(AgentDao.class); var squads = mock(SquadDao.class); var members = mock(SquadMemberDao.class);
        var stores = mock(MemoryStoreApplicationService.class); var migration = mock(LegacyMemoryMigrationService.class);
        var agent = new AgentDO(); agent.setId(42L); agent.setTenantId(7L); agent.setName("全栈开发");
        var squad = new SquadDO(); squad.setId(12L); squad.setTenantId(7L); squad.setName("社区小队");
        var member = new SquadMemberDO(); member.setTenantId(7L); member.setAgentId(42L); member.setSquadId(12L);
        when(agents.listByTenant(7L)).thenReturn(List.of(agent));
        when(squads.listByTenant(7L)).thenReturn(List.of(squad));
        when(members.listByAgentIds(7L, List.of(42L))).thenReturn(List.of(member));
        when(stores.listForUser(7, 9, true)).thenReturn(List.of());
        when(migration.progress(7)).thenReturn(new LegacyMemoryMigrationService.Progress(3, 1));
        var result = new MemoryDirectoryService(agents, squads, members, stores, migration).list(7, 9, true);
        assertEquals(3, result.owners().size());
        var owner = result.owners().stream().filter(o -> "AGENT".equals(o.scope())).findFirst().orElseThrow();
        assertEquals("全栈开发", owner.name()); assertEquals(List.of("社区小队"), owner.squadNames());
        assertNull(owner.storeId()); assertTrue(owner.canCreate());
        assertEquals(1, result.migration().pending());
        verify(stores, never()).createStore(anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test void documentMetadataDoesNotReadTitleFromBodyAndKeepsOpaquePath() {
        var document = new MemoryDocumentDO(); document.setPath("mcp-123.md");
        document.setContentMd("---\ntype: feedback\ntitle: 发布约定\ndescription: 同步时\nsource_memory_id: 41\n---\n正文\ntitle: 不是标题\n");
        MemoryDirectoryService.describe(document);
        assertEquals("发布约定", document.getTitle()); assertEquals("feedback", document.getMemoryType());
        assertEquals("正文\ntitle: 不是标题\n", document.getBody());
        assertEquals("mcp-123.md", document.getPath());
    }
}
