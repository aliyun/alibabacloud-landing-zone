package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 输入装配必须只读取线上版本、剔除跨工作空间与已删除配置，并把缺失项写进输入而不是编造内容；
 * 同时 payload 与其 SHA-256 必须始终来自同一份字节，否则缓存键会与 Chief 看到的输入脱节。
 */
class WorkflowInputAssemblerTest {

    private static final long WORKSPACE = 100L;
    private static final long SQUAD = 5L;

    private SquadDao squadDao;
    private SquadMemberDao memberDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private SdlcDao sdlcDao;
    private SdlcStepDao sdlcStepDao;
    private WorkflowInputAssembler assembler;

    @BeforeEach
    void setUp() {
        squadDao = mock(SquadDao.class);
        memberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        sdlcDao = mock(SdlcDao.class);
        sdlcStepDao = mock(SdlcStepDao.class);
        assembler = new WorkflowInputAssembler(squadDao, memberDao, agentDao, agentVersionDao,
                sdlcDao, sdlcStepDao, 2 * 1024 * 1024);
        when(squadDao.findById(SQUAD)).thenReturn(squad(WORKSPACE, 0));
        when(agentVersionDao.listByIds(anyLong(), anyCollection())).thenReturn(List.of());
        when(sdlcDao.listByIds(anyCollection())).thenReturn(List.of());
        when(sdlcStepDao.listBySdlcIds(anyCollection())).thenReturn(List.of());
    }

    @Test
    void assemblesEveryOnlineMemberInStableAgentOrderWithVerbatimSources() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(
                member(SQUAD, 12L, WORKSPACE), member(SQUAD, 11L, WORKSPACE),
                member(SQUAD, 11L, WORKSPACE), member(SQUAD, 13L, WORKSPACE + 1),
                member(SQUAD, null, WORKSPACE)));
        AgentDO eleven = agent(11L, WORKSPACE, 88L, "实现者");
        AgentDO twelve = agent(12L, WORKSPACE, null, "待配置");
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection())).thenReturn(List.of(twelve, eleven));
        AgentVersionDO version = version(88L, 11L, "开发", "AW_FS_DEV", 7L,
                "负责实现需求", "业务背景");
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection())).thenReturn(List.of(version));
        when(sdlcDao.listByIds(anyCollection())).thenReturn(List.of(sdlc(7L, WORKSPACE, "交付流程")));
        SdlcStepDO second = step(22L, 7L, 2, "验收", "GATE", false);
        SdlcStepDO first = step(21L, 7L, 1, "实现", "EXECUTE", true);
        when(sdlcStepDao.listBySdlcIds(anyCollection())).thenReturn(List.of(second, first));

        WorkflowInput input = assembler.assemble(WORKSPACE, SQUAD);

        assertEquals(List.of("11", "12"),
                input.members().stream().map(WorkflowInput.Member::agentId).toList());
        WorkflowInput.Member implementing = input.members().get(0);
        assertEquals("88", implementing.onlineVersionId());
        assertEquals("7", implementing.sdlcId());
        assertEquals(List.of("21", "22"),
                implementing.steps().stream().map(WorkflowInput.Step::stepId).toList());
        assertTrue(implementing.missingConfiguration().isEmpty());
        assertEquals(List.of(WorkflowInputAssembler.MISSING_ONLINE_VERSION),
                input.members().get(1).missingConfiguration());

        assertEquals(List.of("agent:11:agent-md", "agent:11:soul-md", "agent:11:sdlc:name",
                        "agent:11:sdlc:workType", "agent:11:sdlc:step:21:name",
                        "agent:11:sdlc:step:21:kind", "agent:11:sdlc:step:21:instructionMd",
                        "agent:11:sdlc:step:22:name", "agent:11:sdlc:step:22:kind",
                        "agent:11:sdlc:step:22:instructionMd"),
                input.sources().stream().map(WorkflowInput.Source::sourceId).toList());
        assertEquals("负责实现需求", input.sources().get(0).content());
        assertEquals(WorkflowInput.ANALYSIS_VERSION, input.analysisVersion());
    }

    @Test
    void payloadHashAlwaysMatchesTheSerializedBytesAndIsStable() {
        stubSingleCompleteMember();

        WorkflowInput first = assembler.assemble(WORKSPACE, SQUAD);
        WorkflowInput second = assembler.assemble(WORKSPACE, SQUAD);

        assertEquals(first.inputHash(), second.inputHash());
        assertEquals(WorkflowInputAssembler.sha256Hex(first.payloadJson()), first.inputHash());
        assertEquals(64, first.inputHash().length());
        assertTrue(first.payloadJson().contains("\"analysisVersion\":\"" + WorkflowInput.ANALYSIS_VERSION + "\""));
        assertTrue(first.payloadJson().contains("\"members\""));
    }

    @Test
    void rejectsMissingForeignOrDeletedSquads() {
        when(squadDao.findById(SQUAD)).thenReturn(null);
        assertSquadNotFound();

        when(squadDao.findById(SQUAD)).thenReturn(squad(WORKSPACE + 1, 0));
        assertSquadNotFound();

        when(squadDao.findById(SQUAD)).thenReturn(squad(WORKSPACE, 1));
        assertSquadNotFound();
    }

    @Test
    void rejectsASquadWithoutAnyUsableMember() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(
                member(SQUAD, 11L, WORKSPACE + 1), member(SQUAD, null, WORKSPACE)));

        BizException thrown = assertThrows(BizException.class,
                () -> assembler.assemble(WORKSPACE, SQUAD));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD.getCode(), thrown.getCode());
    }

    @Test
    void recordsEveryKindOfMissingMemberConfigurationInsteadOfInventingContent() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(
                member(SQUAD, 11L, WORKSPACE), member(SQUAD, 12L, WORKSPACE),
                member(SQUAD, 13L, WORKSPACE), member(SQUAD, 14L, WORKSPACE)));
        AgentDO twelve = agent(12L, WORKSPACE, null, "无线上版本");
        AgentDO thirteen = agent(13L, WORKSPACE, 99L, "版本丢失");
        AgentDO fourteen = agent(14L, WORKSPACE, 101L, "版本错绑");
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(twelve, thirteen, fourteen));
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(version(101L, 99L, "别的成员", "X", null, null, null)));

        WorkflowInput input = assembler.assemble(WORKSPACE, SQUAD);

        assertEquals(List.of(WorkflowInputAssembler.MISSING_AGENT),
                input.members().get(0).missingConfiguration());
        assertEquals(List.of(WorkflowInputAssembler.MISSING_ONLINE_VERSION),
                input.members().get(1).missingConfiguration());
        assertEquals(List.of(WorkflowInputAssembler.MISSING_VERSION),
                input.members().get(2).missingConfiguration());
        assertEquals(List.of(WorkflowInputAssembler.MISSING_VERSION),
                input.members().get(3).missingConfiguration());
        assertTrue(input.sources().isEmpty());
    }

    @Test
    void disclosesBlankDocumentsAndMissingSdlcBinding() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(member(SQUAD, 11L, WORKSPACE)));
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(agent(11L, WORKSPACE, 88L, "空配置")));
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(version(88L, 11L, "开发", "X", null, null, null)));

        WorkflowInput input = assembler.assemble(WORKSPACE, SQUAD);

        assertEquals(List.of(WorkflowInputAssembler.MISSING_AGENT_MD,
                        WorkflowInputAssembler.MISSING_SOUL_MD,
                        WorkflowInputAssembler.MISSING_SDLC),
                input.members().get(0).missingConfiguration());
        assertTrue(input.sources().isEmpty());
    }

    @Test
    void ignoresDeletedOrForeignSdlsAndDeletedSteps() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(member(SQUAD, 11L, WORKSPACE)));
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(agent(11L, WORKSPACE, 88L, "实现者")));
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(version(88L, 11L, "开发", "X", 7L, "负责实现", "背景")));
        SdlcDO deleted = sdlc(7L, WORKSPACE, "已删除");
        deleted.setIsDeleted(1);
        when(sdlcDao.listByIds(anyCollection())).thenReturn(List.of(deleted));

        WorkflowInput deletedSdlc = assembler.assemble(WORKSPACE, SQUAD);
        assertEquals(List.of(WorkflowInputAssembler.MISSING_SDLC),
                deletedSdlc.members().get(0).missingConfiguration());

        when(sdlcDao.listByIds(anyCollection()))
                .thenReturn(List.of(sdlc(7L, WORKSPACE + 1, "别人的流程")));
        assertEquals(List.of(WorkflowInputAssembler.MISSING_SDLC),
                assembler.assemble(WORKSPACE, SQUAD).members().get(0).missingConfiguration());

        when(sdlcDao.listByIds(anyCollection())).thenReturn(List.of(sdlc(7L, WORKSPACE, "交付流程")));
        SdlcStepDO deletedStep = step(20L, 7L, 0, "已删除步骤", "EXECUTE", true);
        deletedStep.setIsDeleted(1);
        when(sdlcStepDao.listBySdlcIds(anyCollection())).thenReturn(List.of(
                deletedStep, step(22L, 7L, null, "无序号", "GATE", false),
                step(21L, 7L, 1, "实现", "EXECUTE", true)));

        WorkflowInput steps = assembler.assemble(WORKSPACE, SQUAD);

        assertEquals(List.of("21", "22"),
                steps.members().get(0).steps().stream().map(WorkflowInput.Step::stepId).toList());
        assertFalse(steps.sources().stream()
                .anyMatch(source -> "20".equals(source.stepId())));
    }

    @Test
    void rejectsAnInputThatExceedsTheConfiguredByteBudget() {
        WorkflowInputAssembler tiny = new WorkflowInputAssembler(squadDao, memberDao, agentDao,
                agentVersionDao, sdlcDao, sdlcStepDao, 32);
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(member(SQUAD, 11L, WORKSPACE)));
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(agent(11L, WORKSPACE, 88L, "实现者")));
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(version(88L, 11L, "开发", "X", null, "负责实现", "背景")));

        BizException thrown = assertThrows(BizException.class,
                () -> tiny.assemble(WORKSPACE, SQUAD));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_INPUT_TOO_LARGE.getCode(), thrown.getCode());
    }

    @Test
    void toleratesEmptyDaoResponses() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(member(SQUAD, 11L, WORKSPACE)));
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection())).thenReturn(null);
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection())).thenReturn(null);
        when(sdlcDao.listByIds(anyCollection())).thenReturn(null);
        when(sdlcStepDao.listBySdlcIds(anyCollection())).thenReturn(null);

        WorkflowInput input = assembler.assemble(WORKSPACE, SQUAD);

        assertEquals(List.of(WorkflowInputAssembler.MISSING_AGENT),
                input.members().get(0).missingConfiguration());
        assertTrue(input.sources().isEmpty());
    }

    private void stubSingleCompleteMember() {
        when(memberDao.listBySquad(SQUAD)).thenReturn(List.of(member(SQUAD, 11L, WORKSPACE)));
        when(agentDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(agent(11L, WORKSPACE, 88L, "实现者")));
        when(agentVersionDao.listByIds(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(version(88L, 11L, "开发", "AW_FS_DEV", 7L,
                        "负责实现需求", "业务背景")));
        when(sdlcDao.listByIds(anyCollection())).thenReturn(List.of(sdlc(7L, WORKSPACE, "交付流程")));
        when(sdlcStepDao.listBySdlcIds(anyCollection()))
                .thenReturn(List.of(step(21L, 7L, 1, "实现", "EXECUTE", true)));
    }

    private void assertSquadNotFound() {
        BizException thrown = assertThrows(BizException.class,
                () -> assembler.assemble(WORKSPACE, SQUAD));
        assertEquals(ErrorCode.SQUAD_NOT_FOUND.getCode(), thrown.getCode());
    }

    private SquadDO squad(long tenantId, int isDeleted) {
        SquadDO squad = new SquadDO();
        squad.setId(SQUAD);
        squad.setTenantId(tenantId);
        squad.setName("交付小队");
        squad.setIsDeleted(isDeleted);
        return squad;
    }

    private SquadMemberDO member(long squadId, Long agentId, long tenantId) {
        SquadMemberDO row = new SquadMemberDO();
        row.setSquadId(squadId);
        row.setAgentId(agentId);
        row.setTenantId(tenantId);
        return row;
    }

    private AgentDO agent(long id, long tenantId, Long onlineVersionId, String name) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(tenantId);
        agent.setOnlineVersionId(onlineVersionId);
        agent.setName(name);
        return agent;
    }

    private AgentVersionDO version(long id, long agentId, String roleName, String roleCode,
            Long sdlcId, String responsibilities, String businessBackground) {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(id);
        version.setAgentId(agentId);
        version.setTenantId(WORKSPACE);
        version.setRoleName(roleName);
        version.setRoleCode(roleCode);
        version.setSdlcId(sdlcId);
        version.setResponsibilities(responsibilities);
        version.setBusinessBackground(businessBackground);
        return version;
    }

    private SdlcDO sdlc(long id, long tenantId, String name) {
        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(id);
        sdlc.setTenantId(tenantId);
        sdlc.setName(name);
        sdlc.setDescription(null);
        sdlc.setWorkType("REQ");
        return sdlc;
    }

    private SdlcStepDO step(long id, long sdlcId, Integer order, String name, String kind,
            boolean required) {
        SdlcStepDO step = new SdlcStepDO();
        step.setId(id);
        step.setSdlcId(sdlcId);
        step.setStepOrder(order);
        step.setName(name);
        step.setKind(kind);
        step.setRequired(required);
        step.setInstructionMd(name + "说明");
        return step;
    }
}
