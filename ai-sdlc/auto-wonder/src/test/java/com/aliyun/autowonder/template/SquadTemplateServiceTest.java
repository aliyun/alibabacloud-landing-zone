package com.aliyun.autowonder.template;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.template.dto.ApplyResultVO;
import com.aliyun.autowonder.template.dto.SquadTemplateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SquadTemplateServiceTest {

    SquadTemplateDao templateDao;
    SquadDao squadDao;
    SquadMemberDao squadMemberDao;
    AgentDao agentDao;
    AgentVersionDao agentVersionDao;
    AgentRepoPermDao agentRepoPermDao;
    SdlcDao sdlcDao;
    SdlcStepDao sdlcStepDao;
    RepoDao repoDao;
    SquadTemplateService service;

    @BeforeEach
    void setUp() {
        templateDao = mock(SquadTemplateDao.class);
        squadDao = mock(SquadDao.class);
        squadMemberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        agentRepoPermDao = mock(AgentRepoPermDao.class);
        sdlcDao = mock(SdlcDao.class);
        sdlcStepDao = mock(SdlcStepDao.class);
        repoDao = mock(RepoDao.class);
        service = new SquadTemplateService(templateDao, squadDao, squadMemberDao,
                agentDao, agentVersionDao, agentRepoPermDao, sdlcDao, sdlcStepDao, repoDao);
    }

    @Test
    void list_returns_templates_for_tenant() {
        SquadTemplateDO t1 = new SquadTemplateDO();
        t1.setId(1L);
        t1.setName("独立开发者");
        t1.setDescription("一人全栈");
        t1.setSquadSize(1);
        t1.setIcon("solo");
        t1.setTags("推荐,快速");
        t1.setTenantId(null);

        when(templateDao.listActive(100L)).thenReturn(List.of(t1));

        List<SquadTemplateVO> result = service.list(100L);
        assertEquals(1, result.size());
        assertEquals("独立开发者", result.get(0).getName());
        assertEquals(1, result.get(0).getSquadSize());
        assertEquals(List.of("推荐", "快速"), result.get(0).getTags());
        assertTrue(result.get(0).isSystem());
    }

    @Test
    void list_handles_null_tags() {
        SquadTemplateDO t = new SquadTemplateDO();
        t.setId(2L);
        t.setName("测试");
        t.setDescription("desc");
        t.setSquadSize(2);
        t.setTags(null);
        t.setTenantId(50L);

        when(templateDao.listActive(50L)).thenReturn(List.of(t));

        List<SquadTemplateVO> result = service.list(50L);
        assertEquals(List.of(), result.get(0).getTags());
        assertFalse(result.get(0).isSystem());
    }

    @Test
    void apply_throws_when_template_not_found() {
        when(templateDao.findById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.apply(999L, 100L, 7L));
        assertEquals("15010", ex.getCode());
    }

    @Test
    void apply_creates_squad_and_agents() {
        SquadTemplateDO template = new SquadTemplateDO();
        template.setId(1L);
        template.setName("独立开发者");
        template.setContentJson("{\"squad\":{\"name\":\"测试小队\",\"description\":\"描述\"},"
                + "\"agents\":[{\"name\":\"Dev\",\"roleCode\":\"FS_DEV\",\"roleName\":\"全栈开发\","
                + "\"businessBackground\":\"\",\"responsibilities\":\"编码\","
                + "\"sdlc\":{\"name\":\"DevSDLC\",\"description\":\"开发流程\","
                + "\"steps\":[{\"order\":1,\"name\":\"编码\",\"kind\":\"WORK\",\"instruction\":\"写代码\"}]}}]}");

        when(templateDao.findById(1L)).thenReturn(template);
        when(repoDao.list(100L, 0, 200)).thenReturn(List.of(makeRepo(10L), makeRepo(20L)));

        doAnswer(inv -> {
            SquadDO s = inv.getArgument(0);
            s.setId(500L);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));

        doAnswer(inv -> {
            SdlcDO s = inv.getArgument(0);
            s.setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));

        doAnswer(inv -> {
            SdlcStepDO s = inv.getArgument(0);
            s.setId(700L);
            return null;
        }).when(sdlcStepDao).insert(any(SdlcStepDO.class));

        doAnswer(inv -> {
            AgentDO a = inv.getArgument(0);
            a.setId(800L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));

        doAnswer(inv -> {
            AgentVersionDO v = inv.getArgument(0);
            v.setId(900L);
            return null;
        }).when(agentVersionDao).insert(any(AgentVersionDO.class));

        ApplyResultVO result = service.apply(1L, 100L, 7L);

        assertEquals(500L, result.getSquadId());
        assertEquals(1, result.getAgents().size());
        assertEquals(800L, result.getAgents().get(0).getAgentId());
        assertEquals("全栈开发", result.getAgents().get(0).getRoleName());
        assertEquals("FS_DEV", result.getAgents().get(0).getRoleCode());

        verify(squadDao).insert(any(SquadDO.class));
        verify(sdlcDao).insert(any(SdlcDO.class));
        verify(sdlcStepDao).insert(any(SdlcStepDO.class));
        verify(agentDao).insert(any(AgentDO.class));
        verify(agentVersionDao).insert(any(AgentVersionDO.class));
        verify(squadMemberDao).insert(any());
        verify(agentRepoPermDao, times(2)).insert(any(AgentRepoPermDO.class));
        verify(sdlcDao).updateStatus(eq(600L), eq(100L), eq("ENABLED"), eq(700L), eq(0), eq(7L));
        verify(agentDao).updateStatus(eq(800L), eq(100L), eq("ONLINE"), eq(900L), isNull(), eq(1), eq(0), eq(7L));
    }

    /** 回归：模板创建的数字人必须显式初始化 kind=STANDARD（与 AgentService.create 一致），
     *  否则 AgentDao.xml 的 INSERT 绑定 SQL NULL 违反 agent.kind NOT NULL（V055）。 */
    @Test
    void apply_inserts_agent_with_standard_kind() {
        SquadTemplateDO template = new SquadTemplateDO();
        template.setId(1L);
        template.setName("独立开发者");
        template.setContentJson("{\"squad\":{\"name\":\"测试小队\",\"description\":\"描述\"},"
                + "\"agents\":[{\"name\":\"Dev\",\"roleCode\":\"FS_DEV\",\"roleName\":\"全栈开发\","
                + "\"businessBackground\":\"\",\"responsibilities\":\"编码\","
                + "\"sdlc\":{\"name\":\"DevSDLC\",\"description\":\"开发流程\","
                + "\"steps\":[{\"order\":1,\"name\":\"编码\",\"kind\":\"WORK\",\"instruction\":\"写代码\"}]}}]}");

        when(templateDao.findById(1L)).thenReturn(template);
        when(repoDao.list(100L, 0, 200)).thenReturn(List.of());

        doAnswer(inv -> {
            SquadDO s = inv.getArgument(0);
            s.setId(500L);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));

        doAnswer(inv -> {
            SdlcDO s = inv.getArgument(0);
            s.setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));

        doAnswer(inv -> {
            SdlcStepDO s = inv.getArgument(0);
            s.setId(700L);
            return null;
        }).when(sdlcStepDao).insert(any(SdlcStepDO.class));

        doAnswer(inv -> {
            AgentDO a = inv.getArgument(0);
            a.setId(800L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));

        doAnswer(inv -> {
            AgentVersionDO v = inv.getArgument(0);
            v.setId(900L);
            return null;
        }).when(agentVersionDao).insert(any(AgentVersionDO.class));

        service.apply(1L, 100L, 7L);

        ArgumentCaptor<AgentDO> captor = ArgumentCaptor.forClass(AgentDO.class);
        verify(agentDao).insert(captor.capture());
        AgentDO inserted = captor.getValue();
        assertEquals("STANDARD", inserted.getKind());
        assertEquals(100L, inserted.getTenantId());
        assertEquals("Dev", inserted.getName());
        assertEquals("ONLINE", inserted.getStatus());
        assertEquals(1, inserted.getLatestVersionNo());
        assertEquals(7L, inserted.getCreatorId());
        assertEquals(0, inserted.getVersion());
    }

    /** 回归：agent 插入失败（真实库中即 kind NOT NULL 约束违反）必须从 apply 抛出，
     *  由 @Transactional 回滚已创建的小队/SDLC/步骤，不得吞掉异常造成部分成功。 */
    @Test
    void apply_propagates_agent_insert_failure_for_rollback() {
        SquadTemplateDO template = new SquadTemplateDO();
        template.setId(1L);
        template.setName("独立开发者");
        template.setContentJson("{\"squad\":{\"name\":\"测试小队\",\"description\":\"描述\"},"
                + "\"agents\":[{\"name\":\"Dev\",\"roleCode\":\"FS_DEV\",\"roleName\":\"全栈开发\","
                + "\"businessBackground\":\"\",\"responsibilities\":\"编码\","
                + "\"sdlc\":{\"name\":\"DevSDLC\",\"description\":\"开发流程\","
                + "\"steps\":[{\"order\":1,\"name\":\"编码\",\"kind\":\"WORK\",\"instruction\":\"写代码\"}]}}]}");

        when(templateDao.findById(1L)).thenReturn(template);
        when(repoDao.list(100L, 0, 200)).thenReturn(List.of());

        // apply 会把 squad.getId() 拆箱成 long 传给 createAgentFromTemplate，需要 mock 回填 id
        doAnswer(inv -> {
            SquadDO s = inv.getArgument(0);
            s.setId(500L);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));

        doThrow(new DataIntegrityViolationException("Column 'kind' cannot be null"))
                .when(agentDao).insert(any(AgentDO.class));

        assertThrows(DataIntegrityViolationException.class, () -> service.apply(1L, 100L, 7L));
    }

    /** 系统小队模板逐个应用的回归：仓库播种的每个系统模板都必须应用成功，
     *  且每个模板创建的 agent 都显式带 kind=STANDARD，防止模板内容变更后再次触发 agent.kind NOT NULL。 */
    @Test
    void apply_seeds_all_system_templates_with_standard_kind() throws IOException {
        List<String> contents = systemTemplateContents();
        assertEquals(3, contents.size(),
                "系统小队模板播种数量变化，请同步更新本测试并复核模板应用链路");

        stubInsertGeneratedIds();
        for (String content : contents) {
            int agentCount = JSON.parseObject(content).getJSONArray("agents").size();
            assertTrue(agentCount > 0, "系统模板未定义 agents：" + content);

            SquadTemplateDO template = new SquadTemplateDO();
            template.setId(1L);
            template.setName("system-template");
            template.setContentJson(content);
            when(templateDao.findById(1L)).thenReturn(template);
            when(repoDao.list(100L, 0, 200)).thenReturn(List.of());
            clearInvocations(agentDao);

            service.apply(1L, 100L, 7L);

            ArgumentCaptor<AgentDO> captor = ArgumentCaptor.forClass(AgentDO.class);
            verify(agentDao, times(agentCount)).insert(captor.capture());
            for (AgentDO inserted : captor.getAllValues()) {
                assertEquals("STANDARD", inserted.getKind(),
                        "模板创建的 agent 必须显式设置 kind: " + inserted.getName());
            }
        }
    }

    private void stubInsertGeneratedIds() {
        doAnswer(inv -> {
            SquadDO s = inv.getArgument(0);
            s.setId(500L);
            return null;
        }).when(squadDao).insert(any(SquadDO.class));

        doAnswer(inv -> {
            SdlcDO s = inv.getArgument(0);
            s.setId(600L);
            return null;
        }).when(sdlcDao).insert(any(SdlcDO.class));

        doAnswer(inv -> {
            SdlcStepDO s = inv.getArgument(0);
            s.setId(700L);
            return null;
        }).when(sdlcStepDao).insert(any(SdlcStepDO.class));

        doAnswer(inv -> {
            AgentDO a = inv.getArgument(0);
            a.setId(800L);
            return null;
        }).when(agentDao).insert(any(AgentDO.class));

        doAnswer(inv -> {
            AgentVersionDO v = inv.getArgument(0);
            v.setId(900L);
            return null;
        }).when(agentVersionDao).insert(any(AgentVersionDO.class));
    }

    /** 从系统小队模板种子 SQL 提取全部 content_json（原库存储值，反斜杠转义已还原）。 */
    private static List<String> systemTemplateContents() throws IOException {
        String sql = Files.readString(findSystemTemplateSeed(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?s)SET @template_[a-z]+ = '(.*?)';").matcher(sql);
        List<String> contents = new ArrayList<>();
        while (matcher.find()) {
            contents.add(unescapeSqlString(matcher.group(1)));
        }
        return contents;
    }

    private static Path findSystemTemplateSeed() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs/autowonder-community-templates.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到系统小队模板种子 SQL: docs/autowonder-community-templates.sql");
    }

    private static String unescapeSqlString(String literal) {
        StringBuilder value = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char current = literal.charAt(i);
            if (current != '\\' || i + 1 >= literal.length()) {
                value.append(current);
                continue;
            }
            char escaped = literal.charAt(++i);
            switch (escaped) {
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                default -> value.append(escaped);
            }
        }
        return value.toString();
    }

    private RepoDO makeRepo(Long id) {
        RepoDO repo = new RepoDO();
        repo.setId(id);
        repo.setName("repo-" + id);
        return repo;
    }
}
