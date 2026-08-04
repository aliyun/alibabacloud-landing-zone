package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillServiceTest {

    private SkillDao skillDao;
    private AgentSkillDao agentSkillDao;
    private UserDao userDao;
    private SkillService service;

    @BeforeEach
    void setUp() {
        skillDao = mock(SkillDao.class);
        agentSkillDao = mock(AgentSkillDao.class);
        userDao = mock(UserDao.class);
        service = new SkillService(skillDao, agentSkillDao, userDao);
    }

    @Test
    void createSuccess() {
        when(skillDao.findByTypeAndName(1L, "MCP", "my-server")).thenReturn(null);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");
        req.setName("my-server");
        req.setInstallSpec("{\"cmd\":\"node\"}");
        req.setDescription("desc");

        SkillVO vo = service.create(req, 1L, 2L);
        assertEquals("MCP", vo.getType());
        assertEquals("my-server", vo.getName());
        verify(skillDao).insert(any());
    }

    @Test
    void createJsonQuotesPlainInstallSpecBeforeInsert() {
        when(skillDao.findByTypeAndName(1L, "SKILL", "alibabacloud-find-skills")).thenReturn(null);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("SKILL");
        req.setName("alibabacloud-find-skills");
        req.setInstallSpec("npx");

        service.create(req, 1L, 2L);

        ArgumentCaptor<SkillDO> captor = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao).insert(captor.capture());
        assertEquals("\"npx\"", captor.getValue().getInstallSpec());
    }

    @Test
    void getReturnsPlainInstallSpecWhenStoredAsJsonString() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setType("SKILL");
        s.setName("alibabacloud-find-skills");
        s.setInstallSpec("\"npx\"");
        when(skillDao.findById(1L)).thenReturn(s);

        SkillVO vo = service.get(1L);

        assertEquals("npx", vo.getInstallSpec());
    }

    @Test
    void getReturnsModifiedMetadataAndModifierName() {
        Date modifiedAt = new Date(1783900000000L);
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setType("MCP");
        s.setName("github-mcp");
        s.setInstallSpec("\"npx @anthropic/mcp-server-github\"");
        s.setGmtModified(modifiedAt);
        s.setCreatorId(10L);
        s.setModifierId(20L);
        UserDO modifier = new UserDO();
        modifier.setId(20L);
        modifier.setNickname("蔡何");
        modifier.setUsername("caihe");
        when(skillDao.findById(1L)).thenReturn(s);
        when(userDao.findById(20L)).thenReturn(modifier);

        SkillVO vo = service.get(1L);

        assertEquals(modifiedAt, vo.getGmtModified());
        assertEquals(20L, vo.getModifierId());
        assertEquals("蔡何", vo.getModifierName());
    }

    @Test
    void createDuplicateNameThrows() {
        SkillDO existing = new SkillDO();
        existing.setId(50L);
        when(skillDao.findByTypeAndName(1L, "MCP", "dup")).thenReturn(existing);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");
        req.setName("dup");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_DUPLICATE_NAME.getCode(), ex.getCode());
    }

    @Test
    void createMissingNameThrows() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_NAME_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void createMissingTypeThrows() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setName("x");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_TYPE_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void genericCreateRejectsPluginWithoutPackageMetadata() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("PLUGIN");
        req.setName("team-tools");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void genericUpdateRejectsExistingPlugin() {
        SkillDO plugin = new SkillDO();
        plugin.setId(9L);
        plugin.setType("PLUGIN");
        when(skillDao.findById(9L)).thenReturn(plugin);

        BizException ex = assertThrows(BizException.class,
                () -> service.update(9L, new UpdateSkillRequest(), 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(skillDao, never()).update(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void getNotFoundThrows() {
        when(skillDao.findById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(ErrorCode.SKILL_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void deleteInUseThrows() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(0);
        when(skillDao.findById(1L)).thenReturn(s);
        when(agentSkillDao.countBySkillId(1L, 1L)).thenReturn(2);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.SKILL_DELETE_IN_USE.getCode(), ex.getCode());
    }

    @Test
    void deleteSuccess() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(0);
        when(skillDao.findById(1L)).thenReturn(s);
        when(agentSkillDao.countBySkillId(1L, 1L)).thenReturn(0);
        when(skillDao.softDelete(1L, 1L, 0, 2L)).thenReturn(1);

        service.delete(1L, 1L, 2L);
        verify(skillDao).softDelete(1L, 1L, 0, 2L);
    }

    @Test
    void updateVersionConflictThrows() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(3);
        s.setName("old");
        s.setType("MCP");
        s.setInstallSpec("{}");
        s.setDescription("d");
        when(skillDao.findById(1L)).thenReturn(s);
        when(skillDao.update(eq(1L), eq(1L), any(), any(), any(), any(), eq(3), eq(2L))).thenReturn(0);

        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setName("new");
        BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_VERSION_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void updateJsonQuotesPlainInstallSpecBeforePersisting() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(3);
        s.setName("old");
        s.setType("SKILL");
        s.setInstallSpec("\"old\"");
        s.setDescription("d");
        when(skillDao.findById(1L)).thenReturn(s);
        when(skillDao.update(eq(1L), eq(1L), eq("old"), eq("SKILL"), eq("\"npx\""), eq("d"), eq(3), eq(2L))).thenReturn(1);

        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setInstallSpec("npx");
        service.update(1L, req, 1L, 2L);

        verify(skillDao).update(eq(1L), eq(1L), eq("old"), eq("SKILL"), eq("\"npx\""), eq("d"), eq(3), eq(2L));
    }

    @Test
    void updateFromPackageReferenceUsesOneVersionedWrite() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(3);
        s.setName("old");
        s.setType("SKILL");
        s.setInstallSpec("\"old\"");
        s.setDescription("d");
        when(skillDao.findById(1L)).thenReturn(s);
        when(skillDao.updatePackage(eq(1L), eq(1L), eq("SKILL"), eq("\"new\""), eq("old"), eq("d"),
                eq("OSS_ZIP"), eq("oss://skills/new.zip"), isNull(), isNull(), eq("abc"), eq(3), eq(2L)))
                .thenReturn(1);

        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setInstallSpec("new");
        service.updateFromPackageReference(1L, req,
                new SkillService.PackageReference("oss://skills/new.zip", null, null, "abc"), 1L, 2L);

        verify(skillDao, never()).update(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong());
        verify(skillDao).updatePackage(eq(1L), eq(1L), eq("SKILL"), eq("\"new\""), eq("old"), eq("d"),
                eq("OSS_ZIP"), eq("oss://skills/new.zip"), isNull(), isNull(), eq("abc"), eq(3), eq(2L));
    }
}
