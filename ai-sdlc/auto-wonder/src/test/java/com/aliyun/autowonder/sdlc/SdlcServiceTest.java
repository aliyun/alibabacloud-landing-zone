package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.sdlc.dto.*;
import com.aliyun.autowonder.squad.SquadAttributionService;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SdlcServiceTest {

    SdlcDao sdlcDao;
    SdlcStepDao stepDao;
    StatusNodeDao statusNodeDao;
    WorkitemDao workitemDao;
    AgentVersionDao agentVersionDao;
    AgentDao agentDao;
    SdlcService service;

    @BeforeEach
    void setUp() {
        sdlcDao = mock(SdlcDao.class);
        stepDao = mock(SdlcStepDao.class);
        statusNodeDao = mock(StatusNodeDao.class);
        workitemDao = mock(WorkitemDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        agentDao = mock(AgentDao.class);
        service = new SdlcService(sdlcDao, stepDao, statusNodeDao, workitemDao,
                agentVersionDao, agentDao);
    }

    private SdlcDO sdlc(long id, String status) {
        SdlcDO s = new SdlcDO();
        s.setId(id);
        s.setName("flow");
        s.setStatus(status);
        s.setVersion(0);
        return s;
    }

    // stepDao.update 现在整行写回 SdlcStepDO，先固定目标行与审计字段，业务字段由各用例自行断言
    private static boolean stepWrite(SdlcStepDO st) {
        return Long.valueOf(1L).equals(st.getId())
                && Long.valueOf(100L).equals(st.getTenantId())
                && Long.valueOf(7L).equals(st.getModifierId());
    }

    @Test
    void create_sets_draft_status() {
        CreateSdlcRequest req = new CreateSdlcRequest();
        req.setName("Test Flow");
        req.setWorkType("REQ");

        SdlcVO vo = service.create(req, 100L, 7L);

        assertEquals("DRAFT", vo.getStatus());
        assertEquals("Test Flow", vo.getName());
        assertEquals(0, vo.getStepCount());
        verify(sdlcDao).insert(argThat((SdlcDO s) ->
                s.getTenantId() == 100L && "DRAFT".equals(s.getStatus())
                        && "REQ".equals(s.getWorkType())));
    }

    @Test
    void create_blank_name_throws() {
        CreateSdlcRequest req = new CreateSdlcRequest();
        req.setName("");
        BizException ex = assertThrows(BizException.class, () -> service.create(req, 100L, 7L));
        assertEquals("16002", ex.getCode());
    }

    @Test
    void get_not_found_throws() {
        when(sdlcDao.findById(9L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(9L));
        assertEquals("16001", ex.getCode());
    }

    @Test
    void get_returns_vo_with_steps() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("coding");
        step.setHandlerType("AGENT");
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(step));

        SdlcVO vo = service.get(9L);
        assertEquals(9L, vo.getId());
        assertEquals(1, vo.getSteps().size());
        assertEquals("coding", vo.getSteps().get(0).getName());
        // 详情接口带 steps 明细，stepCount 必须与之等长，两个页面口径一致
        assertEquals(1, vo.getStepCount());
    }

    @Test
    void update_enabled_succeeds() {
        SdlcDO s = sdlc(9L, "ENABLED");
        SdlcDO updated = sdlc(9L, "ENABLED");
        updated.setName("new");
        when(sdlcDao.findById(9L)).thenReturn(s).thenReturn(updated);
        when(sdlcDao.update(eq(9L), eq(100L), eq("new"), any(), any(), eq(0), eq(7L))).thenReturn(1);
        UpdateSdlcRequest req = new UpdateSdlcRequest();
        req.setName("new");

        SdlcVO result = service.update(9L, req, 100L, 7L);

        assertEquals("new", result.getName());
    }

    @Test
    void disabled_flow_allows_metadata_and_step_edits() {
        SdlcDO disabled = sdlc(9L, "DISABLED");
        when(sdlcDao.findById(9L)).thenReturn(disabled);
        when(sdlcDao.update(eq(9L), eq(100L), eq("renamed"), any(), any(), eq(0), eq(7L)))
                .thenReturn(1);

        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        SdlcStepDO second = new SdlcStepDO();
        second.setId(2L);
        second.setSdlcId(9L);
        second.setStepOrder(2);
        second.setName("second");
        SdlcStepDO third = new SdlcStepDO();
        third.setId(3L);
        third.setSdlcId(9L);
        third.setStepOrder(3);
        third.setName("new step");
        when(stepDao.findById(1L)).thenReturn(step);
        when(stepDao.listBySdlc(9L))
                .thenReturn(List.of(second, third))
                .thenReturn(List.of(second, third));

        UpdateSdlcRequest updateFlow = new UpdateSdlcRequest();
        updateFlow.setName("renamed");
        service.update(9L, updateFlow, 100L, 7L);

        CreateStepRequest addStep = new CreateStepRequest();
        addStep.setStepOrder(3);
        addStep.setName("new step");
        service.addStep(9L, addStep, 100L, 7L);

        UpdateStepRequest updateStep = new UpdateStepRequest();
        updateStep.setName("updated");
        service.updateStep(9L, 1L, updateStep, 100L, 7L);
        service.deleteStep(9L, 1L, 100L, 7L);

        ReorderRequest reorder = new ReorderRequest();
        reorder.setStepIds(List.of(2L, 3L));
        service.reorderSteps(9L, reorder, 100L, 7L);

        verify(stepDao).insert(any(SdlcStepDO.class));
        verify(stepDao).update(argThat((SdlcStepDO st) ->
                stepWrite(st) && "updated".equals(st.getName())));
        verify(stepDao).softDelete(1L, 100L, 7L);
        verify(stepDao, times(2)).updateOrder(2L, 100L, 1, 7L);
        verify(stepDao, times(2)).updateOrder(3L, 100L, 2, 7L);
    }

    @Test
    void delete_in_use_throws() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(workitemDao.countBySdlcId(9L)).thenReturn(3);
        when(workitemDao.listIdsBySdlcId(9L, 5)).thenReturn(List.of(77L, 78L, 79L));
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 100L, 7L));
        assertEquals("16010", ex.getCode());
        assertTrue(ex.getMessage().contains("工单 3 个(#77, #78, #79)"));
        assertTrue(ex.getMessage().contains("请先解除上述引用后再删除"));
        verify(sdlcDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void delete_in_use_truncates_workitem_sample() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(workitemDao.countBySdlcId(9L)).thenReturn(8);
        when(workitemDao.listIdsBySdlcId(9L, 5)).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 100L, 7L));
        assertEquals("16010", ex.getCode());
        assertTrue(ex.getMessage().contains("工单 8 个(#1, #2, #3, #4, #5 等)"));
    }

    @Test
    void delete_in_use_by_agent_reports_agent_names() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(agentVersionDao.listAgentIdsBySdlcId(9L)).thenReturn(List.of(5L, 6L));
        AgentDO a1 = new AgentDO();
        a1.setId(5L);
        a1.setName("小码");
        when(agentDao.findById(5L)).thenReturn(a1);
        when(agentDao.findById(6L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 100L, 7L));
        assertEquals("16010", ex.getCode());
        assertTrue(ex.getMessage().contains("数字员工 2 个(小码(ID:5), ID:6)"));
        verify(sdlcDao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void delete_without_refs_succeeds() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(sdlcDao.softDelete(9L, 100L, 0, 7L)).thenReturn(1);
        service.delete(9L, 100L, 7L);
        verify(sdlcDao).softDelete(9L, 100L, 0, 7L);
        verify(stepDao).deleteAllBySdlc(9L, 100L);
    }

    @Test
    void list_maps_to_vos() {
        SdlcDO s = sdlc(1L, "DRAFT");
        when(sdlcDao.list(eq("REQ"), isNull(), eq(100L), isNull(), eq(0), eq(20))).thenReturn(List.of(s));
        List<SdlcVO> vos = service.list(100L, "REQ", null, null, 1, 20);
        assertEquals(1, vos.size());
    }

    @Test
    void list_pushes_squad_filter_to_dao_and_fills_attribution() {
        SdlcDO s = sdlc(1L, "ENABLED");
        when(sdlcDao.list(isNull(), isNull(), eq(100L), eq(List.of(7L)), eq(0), eq(20))).thenReturn(List.of(s));
        SquadAttributionService attribution = mock(SquadAttributionService.class);
        service.setSquadAttributionService(attribution);

        List<SdlcVO> vos = service.list(100L, null, null, List.of(7L), 1, 20);

        assertEquals(1, vos.size());
        verify(sdlcDao).list(isNull(), isNull(), eq(100L), eq(List.of(7L)), eq(0), eq(20));
        verify(attribution).fillSdlcSquads(100L, vos);
    }

    @Test
    void list_leaves_squad_fields_null_without_attribution_service() {
        SdlcDO s = sdlc(1L, "DRAFT");
        when(sdlcDao.list(eq("REQ"), isNull(), eq(100L), isNull(), eq(0), eq(20))).thenReturn(List.of(s));

        List<SdlcVO> vos = service.list(100L, "REQ", null, null, 1, 20);

        assertNull(vos.get(0).getSquadIds());
        assertNull(vos.get(0).getSquadNames());
    }

    @Test
    void list_fills_step_count_from_one_batch_query_and_keeps_steps_null() {
        when(sdlcDao.list(isNull(), isNull(), eq(100L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(sdlc(1L, "ENABLED"), sdlc(2L, "DRAFT")));
        when(stepDao.countBySdlcIds(List.of(1L, 2L))).thenReturn(List.of(stepCount(1L, 3)));

        List<SdlcVO> vos = service.list(100L, null, null, null, 1, 20);

        assertEquals(3, vos.get(0).getStepCount());
        // 聚合结果里缺席的 SDLC（无步骤）必须落 0，留 null 会让前端再兜底一次而掩盖问题
        assertEquals(0, vos.get(1).getStepCount());
        // 性能取舍不变：列表仍不返回 steps 明细
        assertNull(vos.get(0).getSteps());
        // 整页一次 IN 聚合，不退化成逐个 SDLC 查询
        verify(stepDao, times(1)).countBySdlcIds(List.of(1L, 2L));
        verify(stepDao, never()).listBySdlc(anyLong());
    }

    @Test
    void list_skips_step_count_query_when_page_is_empty() {
        when(sdlcDao.list(isNull(), isNull(), eq(100L), isNull(), eq(0), eq(20))).thenReturn(List.of());

        assertTrue(service.list(100L, null, null, null, 1, 20).isEmpty());

        verify(stepDao, never()).countBySdlcIds(anyCollection());
    }

    private static SdlcStepCount stepCount(long sdlcId, int cnt) {
        SdlcStepCount count = new SdlcStepCount();
        count.setSdlcId(sdlcId);
        count.setCnt(cnt);
        return count;
    }

    @Test
    void addStep_to_draft_succeeds() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of());
        CreateStepRequest req = new CreateStepRequest();
        req.setStepOrder(1);
        req.setName("coding");
        req.setHandlerType("AGENT");
        req.setHandlerRoleRef("coding");

        StepVO vo = service.addStep(9L, req, 100L, 7L);
        assertEquals(1, vo.getStepOrder());
        verify(stepDao).insert(any());
    }

    @Test
    void addStep_accepts_agent_internal_workflow_instruction_without_handler_type() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        CreateStepRequest req = new CreateStepRequest();
        req.setStepOrder(1);
        req.setName("需求理解和满足性分析");
        req.setKind("analysis");
        req.setInstructionMd("确认需求是否完整、是否可以基于当前上下文完成，并输出风险和缺口。");
        req.setChecklistJson("[{\"id\":\"scope\",\"text\":\"确认范围\"}]");
        req.setGatePolicyJson("{\"evidenceRequired\":true}");
        req.setRequired(true);
        req.setTimeoutSeconds(1800);
        req.setRetryBudget(1);

        StepVO vo = service.addStep(9L, req, 100L, 7L);

        assertEquals("analysis", vo.getKind());
        assertEquals("确认需求是否完整、是否可以基于当前上下文完成，并输出风险和缺口。", vo.getInstructionMd());
        assertEquals(Boolean.TRUE, vo.getRequired());
        verify(stepDao).insert(argThat(step ->
                step.getHandlerType() == null
                        && "analysis".equals(step.getKind())
                        && step.getInstructionMd().contains("确认需求是否完整")
                        && Boolean.TRUE.equals(step.getRequired())));
    }

    @Test
    void addStep_to_enabled_succeeds() {
        SdlcDO s = sdlc(9L, "ENABLED");
        when(sdlcDao.findById(9L)).thenReturn(s);
        CreateStepRequest req = new CreateStepRequest();
        req.setStepOrder(1);
        req.setName("x");
        req.setHandlerType("AGENT");

        StepVO result = service.addStep(9L, req, 100L, 7L);

        assertEquals("x", result.getName());
        verify(stepDao).insert(any(SdlcStepDO.class));
    }

    @Test
    void updateStep_succeeds() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setHandlerType("AGENT");
        when(stepDao.findById(1L)).thenReturn(step);
        SdlcStepDO updatedStep = new SdlcStepDO();
        updatedStep.setId(1L);
        updatedStep.setSdlcId(9L);
        updatedStep.setStepOrder(1);
        updatedStep.setName("new");
        updatedStep.setHandlerType("AGENT");
        when(stepDao.findById(1L)).thenReturn(step).thenReturn(updatedStep);
        when(stepDao.update(any(SdlcStepDO.class))).thenReturn(1);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setName("new");
        req.setHandlerType("AGENT");
        StepVO vo = service.updateStep(9L, 1L, req, 100L, 7L);
        assertEquals("new", vo.getName());
    }

    @Test
    void updateStep_explicitNull_clearsTimeoutAndRetry() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setTimeoutSeconds(600);
        step.setRetryBudget(2);
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setTimeoutSeconds(null);
        req.setRetryBudget(null);
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "old".equals(st.getName())
                && st.getTimeoutSeconds() == null
                && st.getRetryBudget() == null));
    }

    @Test
    void updateStep_absentFields_keepTimeoutAndRetry() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setTimeoutSeconds(600);
        step.setRetryBudget(2);
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setName("new");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "new".equals(st.getName())
                && Integer.valueOf(600).equals(st.getTimeoutSeconds())
                && Integer.valueOf(2).equals(st.getRetryBudget())));
    }

    // handler_role_ref/status_on_enter_code 是 VARCHAR，on_success/on_fail 是 MySQL JSON 列，
    // 因此流转字段的可空值必须是合法 JSON 文本
    private SdlcStepDO stepWithNullableFields() {
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setTenantId(100L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setInstructionMd("old instruction");
        step.setHandlerRoleRef("AW_CR");
        step.setStatusOnEnterCode("aone_172915");
        step.setOnSuccess("{\"to\":\"STEP_2\"}");
        step.setOnFail("{\"to\":\"STOP\"}");
        return step;
    }

    @Test
    void updateStep_absentFields_keepHandlerRoleRefAndTransitions() {
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, "DRAFT"));
        when(stepDao.findById(1L)).thenReturn(stepWithNullableFields());

        UpdateStepRequest req = new UpdateStepRequest();
        req.setName("new");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "new".equals(st.getName())
                && "old instruction".equals(st.getInstructionMd())
                && "AW_CR".equals(st.getHandlerRoleRef())
                && "aone_172915".equals(st.getStatusOnEnterCode())
                && "{\"to\":\"STEP_2\"}".equals(st.getOnSuccess())
                && "{\"to\":\"STOP\"}".equals(st.getOnFail())));
    }

    @Test
    void updateStep_blankHandlerRoleRef_clearsField() {
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, "DRAFT"));
        when(stepDao.findById(1L)).thenReturn(stepWithNullableFields());

        UpdateStepRequest req = new UpdateStepRequest();
        req.setHandlerRoleRef("");
        req.setStatusOnEnterCode("   ");
        req.setOnSuccess("");
        req.setOnFail("  ");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "old".equals(st.getName())
                && st.getHandlerRoleRef() == null
                && st.getStatusOnEnterCode() == null
                && st.getOnSuccess() == null
                && st.getOnFail() == null));
    }

    @Test
    void updateStep_presentTransitions_overwriteExistingValues() {
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, "DRAFT"));
        when(stepDao.findById(1L)).thenReturn(stepWithNullableFields());

        UpdateStepRequest req = new UpdateStepRequest();
        req.setHandlerRoleRef("AW_QA");
        req.setStatusOnEnterCode("aone_100012");
        req.setOnSuccess("{\"to\":\"DONE\"}");
        req.setOnFail("{\"to\":\"REWORK\"}");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "AW_QA".equals(st.getHandlerRoleRef())
                && "aone_100012".equals(st.getStatusOnEnterCode())
                && "{\"to\":\"DONE\"}".equals(st.getOnSuccess())
                && "{\"to\":\"REWORK\"}".equals(st.getOnFail())));
    }

    @Test
    void updateStep_presentScalarFields_overwriteExistingValues() {
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, "DRAFT"));
        SdlcStepDO step = stepWithNullableFields();
        step.setKind("analysis");
        step.setCode("STEP_OLD");
        step.setHandlerType("HUMAN");
        step.setRequired(Boolean.FALSE);
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setKind("test");
        req.setCode("STEP_NEW");
        req.setHandlerType("AGENT");
        req.setRequired(Boolean.TRUE);
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "test".equals(st.getKind())
                && "STEP_NEW".equals(st.getCode())
                && "AGENT".equals(st.getHandlerType())
                && Boolean.TRUE.equals(st.getRequired())));
    }

    @Test
    void updateStep_blankInstructionMd_clearsField() {
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, "DRAFT"));
        when(stepDao.findById(1L)).thenReturn(stepWithNullableFields());

        UpdateStepRequest req = new UpdateStepRequest();
        req.setInstructionMd("   ");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && st.getInstructionMd() == null
                && "AW_CR".equals(st.getHandlerRoleRef())));
    }

    @Test
    void updateStep_writesBackWholeRowPreservingUntouchedColumns() {
        when(sdlcDao.findById(9L)).thenReturn(sdlc(9L, "ENABLED"));
        SdlcStepDO step = stepWithNullableFields();
        step.setKind("test");
        step.setCode("STEP_1");
        step.setHandlerType("AGENT");
        step.setRequired(Boolean.FALSE);
        step.setTimeoutSeconds(900);
        step.setRetryBudget(3);
        step.setCreatorId(5L);
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setName("new");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "new".equals(st.getName())
                && Long.valueOf(9L).equals(st.getSdlcId())
                && Integer.valueOf(1).equals(st.getStepOrder())
                && "test".equals(st.getKind())
                && "STEP_1".equals(st.getCode())
                && "AGENT".equals(st.getHandlerType())
                && Boolean.FALSE.equals(st.getRequired())
                && Integer.valueOf(900).equals(st.getTimeoutSeconds())
                && Integer.valueOf(3).equals(st.getRetryBudget())
                && Long.valueOf(5L).equals(st.getCreatorId())));
    }

    @Test
    void deleteStep_enabled_succeeds() {
        SdlcDO s = sdlc(9L, "ENABLED");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        when(stepDao.findById(1L)).thenReturn(step);
        SdlcStepDO remaining = new SdlcStepDO();
        remaining.setId(2L);
        remaining.setSdlcId(9L);
        remaining.setStepOrder(2);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(remaining));

        service.deleteStep(9L, 1L, 100L, 7L);

        verify(stepDao).softDelete(1L, 100L, 7L);
        verify(stepDao).updateOrder(2L, 100L, 1, 7L);
    }

    @Test
    void enabled_flow_allows_step_update_and_reorder() {
        SdlcDO enabled = sdlc(9L, "ENABLED");
        when(sdlcDao.findById(9L)).thenReturn(enabled);
        SdlcStepDO first = new SdlcStepDO();
        first.setId(1L);
        first.setSdlcId(9L);
        first.setName("old");
        SdlcStepDO second = new SdlcStepDO();
        second.setId(2L);
        second.setSdlcId(9L);
        when(stepDao.findById(1L)).thenReturn(first);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(first, second));

        UpdateStepRequest update = new UpdateStepRequest();
        update.setName("new");
        service.updateStep(9L, 1L, update, 100L, 7L);
        ReorderRequest reorder = new ReorderRequest();
        reorder.setStepIds(List.of(2L, 1L));
        service.reorderSteps(9L, reorder, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) ->
                stepWrite(st) && "new".equals(st.getName())));
        verify(stepDao).updateOrder(2L, 100L, 1, 7L);
        verify(stepDao).updateOrder(1L, 100L, 2, 7L);
    }

    @Test
    void active_flow_allows_step_content_edit() {
        SdlcDO active = sdlc(9L, "ACTIVE");
        when(sdlcDao.findById(9L)).thenReturn(active);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setName("old");
        SdlcStepDO updated = new SdlcStepDO();
        updated.setId(1L);
        updated.setSdlcId(9L);
        updated.setName("old");
        updated.setInstructionMd("new instruction");
        updated.setChecklistJson("[\"编译通过\"]");
        updated.setGatePolicyJson("{\"passCriteria\":\"证据目录非空\"}");
        when(stepDao.findById(1L)).thenReturn(step).thenReturn(updated);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setInstructionMd("new instruction");
        req.setChecklistJson("[\"编译通过\"]");
        req.setGatePolicyJson("{\"passCriteria\":\"证据目录非空\"}");
        StepVO vo = service.updateStep(9L, 1L, req, 100L, 7L);

        assertEquals("new instruction", vo.getInstructionMd());
        assertEquals("[\"编译通过\"]", vo.getChecklistJson());
        assertEquals("{\"passCriteria\":\"证据目录非空\"}", vo.getGatePolicyJson());
        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "old".equals(st.getName())
                && "new instruction".equals(st.getInstructionMd())
                && "[\"编译通过\"]".equals(st.getChecklistJson())
                && "{\"passCriteria\":\"证据目录非空\"}".equals(st.getGatePolicyJson())));
    }

    @Test
    void active_flow_rejects_structural_step_changes() {
        SdlcDO active = sdlc(9L, "ACTIVE");
        when(sdlcDao.findById(9L)).thenReturn(active);

        CreateStepRequest create = new CreateStepRequest();
        create.setName("x");
        BizException addEx = assertThrows(BizException.class,
                () -> service.addStep(9L, create, 100L, 7L));
        assertEquals("16003", addEx.getCode());

        BizException deleteEx = assertThrows(BizException.class,
                () -> service.deleteStep(9L, 1L, 100L, 7L));
        assertEquals("16003", deleteEx.getCode());

        ReorderRequest reorder = new ReorderRequest();
        reorder.setStepIds(List.of(1L));
        BizException reorderEx = assertThrows(BizException.class,
                () -> service.reorderSteps(9L, reorder, 100L, 7L));
        assertEquals("16003", reorderEx.getCode());
    }

    @Test
    void reorder_updates_all_steps() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO st1 = new SdlcStepDO(); st1.setId(1L); st1.setSdlcId(9L);
        SdlcStepDO st2 = new SdlcStepDO(); st2.setId(2L); st2.setSdlcId(9L);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(st1, st2));
        when(stepDao.updateOrder(anyLong(), eq(100L), anyInt(), eq(7L))).thenReturn(1);

        ReorderRequest req = new ReorderRequest();
        req.setStepIds(List.of(2L, 1L));
        service.reorderSteps(9L, req, 100L, 7L);

        verify(stepDao).updateOrder(2L, 100L, 1, 7L);
        verify(stepDao).updateOrder(1L, 100L, 2, 7L);
    }

    @Test
    void reorder_invalid_step_ids_throws() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO st1 = new SdlcStepDO(); st1.setId(1L); st1.setSdlcId(9L);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(st1));

        ReorderRequest req = new ReorderRequest();
        req.setStepIds(List.of(1L, 999L));
        BizException ex = assertThrows(BizException.class, () -> service.reorderSteps(9L, req, 100L, 7L));
        assertEquals("16011", ex.getCode());
    }

    @Test
    void enable_no_steps_throws() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of());
        BizException ex = assertThrows(BizException.class, () -> service.enable(9L, null, 100L, 7L));
        assertEquals("16004", ex.getCode());
    }

    @Test
    void enable_already_enabled_throws() {
        SdlcDO s = sdlc(9L, "ENABLED");
        when(sdlcDao.findById(9L)).thenReturn(s);
        BizException ex = assertThrows(BizException.class, () -> service.enable(9L, null, 100L, 7L));
        assertEquals("16008", ex.getCode());
    }

    @Test
    void enable_ignores_deprecated_routing_targets() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L); step.setSdlcId(9L); step.setStepOrder(1);
        step.setName("coding");
        step.setOnSuccess("{\"action\":\"GOTO_STEP\",\"targetStepId\":999}");
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(step));
        when(sdlcDao.updateStatus(eq(9L), eq(100L), eq("ENABLED"), eq(1L), eq(0), eq(7L))).thenReturn(1);
        SdlcDO enabled = sdlc(9L, "ENABLED");
        enabled.setEntryStepId(1L);
        when(sdlcDao.findById(9L)).thenReturn(s).thenReturn(enabled);

        SdlcVO vo = service.enable(9L, null, 100L, 7L);

        assertEquals("ENABLED", vo.getStatus());
    }

    @Test
    void enable_valid_flow_succeeds() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO s1 = new SdlcStepDO();
        s1.setId(1L); s1.setSdlcId(9L); s1.setStepOrder(1);
        s1.setName("coding"); s1.setHandlerType("AGENT");
        s1.setOnSuccess("{\"action\":\"NEXT_STEP\"}");
        SdlcStepDO s2 = new SdlcStepDO();
        s2.setId(2L); s2.setSdlcId(9L); s2.setStepOrder(2);
        s2.setName("verify"); s2.setHandlerType("HUMAN");
        s2.setOnSuccess("{\"action\":\"END\"}");
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(s1, s2));
        when(sdlcDao.updateStatus(eq(9L), eq(100L), eq("ENABLED"), eq(1L), eq(0), eq(7L))).thenReturn(1);
        // For the get() call after enable
        SdlcDO enabled = sdlc(9L, "ENABLED");
        enabled.setEntryStepId(1L);
        when(sdlcDao.findById(9L)).thenReturn(s).thenReturn(enabled);

        SdlcVO vo = service.enable(9L, null, 100L, 7L);
        verify(sdlcDao).updateStatus(9L, 100L, "ENABLED", 1L, 0, 7L);
    }

    @Test
    void disable_not_enabled_throws() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        BizException ex = assertThrows(BizException.class, () -> service.disable(9L, 100L, 7L));
        assertEquals("16009", ex.getCode());
    }

    @Test
    void disable_enabled_succeeds() {
        SdlcDO s = sdlc(9L, "ENABLED");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(sdlcDao.updateStatus(eq(9L), eq(100L), eq("DISABLED"), isNull(), eq(0), eq(7L))).thenReturn(1);
        service.disable(9L, 100L, 7L);
        verify(sdlcDao).updateStatus(9L, 100L, "DISABLED", null, 0, 7L);
    }

    @Test
    void enable_step_order_gap_succeeds() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO s1 = new SdlcStepDO();
        s1.setId(1L); s1.setSdlcId(9L); s1.setStepOrder(1);
        s1.setName("a"); s1.setHandlerType("AGENT");
        SdlcStepDO s2 = new SdlcStepDO();
        s2.setId(2L); s2.setSdlcId(9L); s2.setStepOrder(3);
        s2.setName("b"); s2.setHandlerType("HUMAN");
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(s1, s2));
        when(sdlcDao.updateStatus(eq(9L), eq(100L), eq("ENABLED"), eq(1L), eq(0), eq(7L))).thenReturn(1);
        SdlcDO enabled = sdlc(9L, "ENABLED");
        enabled.setEntryStepId(1L);
        when(sdlcDao.findById(9L)).thenReturn(s).thenReturn(enabled);

        SdlcVO vo = service.enable(9L, null, 100L, 7L);
        assertEquals("ENABLED", vo.getStatus());
        verify(sdlcDao).updateStatus(9L, 100L, "ENABLED", 1L, 0, 7L);
    }

    @Test
    void deleteStep_renumbers_remaining_steps() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO s1 = new SdlcStepDO(); s1.setId(1L); s1.setSdlcId(9L); s1.setStepOrder(1);
        SdlcStepDO s2 = new SdlcStepDO(); s2.setId(2L); s2.setSdlcId(9L); s2.setStepOrder(2);
        SdlcStepDO s3 = new SdlcStepDO(); s3.setId(3L); s3.setSdlcId(9L); s3.setStepOrder(3);
        when(stepDao.findById(2L)).thenReturn(s2);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(s1, s3));

        service.deleteStep(9L, 2L, 100L, 7L);

        verify(stepDao).softDelete(2L, 100L, 7L);
        verify(stepDao, never()).updateOrder(eq(1L), anyLong(), anyInt(), anyLong());
        verify(stepDao).updateOrder(3L, 100L, 2, 7L);
    }

    @Test
    void deleteStep_no_remaining_steps_skips_renumber() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO s1 = new SdlcStepDO(); s1.setId(1L); s1.setSdlcId(9L); s1.setStepOrder(1);
        when(stepDao.findById(1L)).thenReturn(s1);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of());

        service.deleteStep(9L, 1L, 100L, 7L);

        verify(stepDao).softDelete(1L, 100L, 7L);
        verify(stepDao, never()).updateOrder(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void addStep_null_stepOrder_auto_calculates() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO existing = new SdlcStepDO();
        existing.setId(1L); existing.setSdlcId(9L); existing.setStepOrder(2);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(existing));
        CreateStepRequest req = new CreateStepRequest();
        req.setName("new step");
        req.setHandlerType("AGENT");

        StepVO vo = service.addStep(9L, req, 100L, 7L);

        verify(stepDao).insert(argThat(step -> step.getStepOrder() == 3));
    }

    @Test
    void addStep_null_stepOrder_no_existing_steps_defaults_to_1() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of());
        CreateStepRequest req = new CreateStepRequest();
        req.setName("first");
        req.setHandlerType("AGENT");

        service.addStep(9L, req, 100L, 7L);

        verify(stepDao).insert(argThat(step -> step.getStepOrder() == 1));
    }

    @Test
    void addStep_duplicate_key_retries_with_recalculated_order() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO existing = new SdlcStepDO();
        existing.setId(1L); existing.setSdlcId(9L); existing.setStepOrder(3);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(existing));
        doThrow(new DuplicateKeyException("uk_sdlc_order"))
                .doNothing()
                .when(stepDao).insert(any(SdlcStepDO.class));
        CreateStepRequest req = new CreateStepRequest();
        req.setStepOrder(3);
        req.setName("conflicting step");

        StepVO vo = service.addStep(9L, req, 100L, 7L);

        verify(stepDao, times(2)).insert(any(SdlcStepDO.class));
        assertEquals(4, vo.getStepOrder());
    }

    @Test
    void addStep_duplicate_key_exhausted_throws_business_error_not_10000() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of());
        doThrow(new DuplicateKeyException("uk_sdlc_order"))
                .when(stepDao).insert(any(SdlcStepDO.class));
        CreateStepRequest req = new CreateStepRequest();
        req.setName("always conflicting");

        BizException ex = assertThrows(BizException.class,
                () -> service.addStep(9L, req, 100L, 7L));

        assertEquals("16012", ex.getCode());
        verify(stepDao, times(3)).insert(any(SdlcStepDO.class));
    }

    @Test
    void addStep_non_positive_stepOrder_is_recalculated_server_side() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO existing = new SdlcStepDO();
        existing.setId(1L); existing.setSdlcId(9L); existing.setStepOrder(1);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(existing));
        CreateStepRequest req = new CreateStepRequest();
        req.setStepOrder(0);
        req.setName("sanitized");

        service.addStep(9L, req, 100L, 7L);

        verify(stepDao).insert(argThat(step -> step.getStepOrder() == 2));
    }

    @Test
    void reorder_uses_negative_temp_band_before_assigning_target_orders() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO st1 = new SdlcStepDO(); st1.setId(1L); st1.setSdlcId(9L); st1.setStepOrder(1);
        SdlcStepDO st2 = new SdlcStepDO(); st2.setId(2L); st2.setSdlcId(9L); st2.setStepOrder(2);
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(st1, st2));
        when(stepDao.updateOrder(anyLong(), eq(100L), anyInt(), eq(7L))).thenReturn(1);

        ReorderRequest req = new ReorderRequest();
        req.setStepIds(List.of(2L, 1L));
        service.reorderSteps(9L, req, 100L, 7L);

        org.mockito.InOrder inOrder = inOrder(stepDao);
        inOrder.verify(stepDao).updateOrder(2L, 100L, Integer.MIN_VALUE, 7L);
        inOrder.verify(stepDao).updateOrder(1L, 100L, Integer.MIN_VALUE + 1, 7L);
        inOrder.verify(stepDao).updateOrder(2L, 100L, 1, 7L);
        inOrder.verify(stepDao).updateOrder(1L, 100L, 2, 7L);
    }

    @Test
    void updateStep_invalid_checklistJson_throws_param_error_not_conflict() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setChecklistJson("{invalid-json");
        BizException ex = assertThrows(BizException.class,
                () -> service.updateStep(9L, 1L, req, 100L, 7L));

        assertEquals("10001", ex.getCode());
        assertTrue(ex.getMessage().contains("checklistJson"));
        verify(stepDao, never()).update(any(SdlcStepDO.class));
    }

    @Test
    void updateStep_invalid_gatePolicyJson_throws_param_error_not_conflict() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setGatePolicyJson("coverageThreshold:80");
        BizException ex = assertThrows(BizException.class,
                () -> service.updateStep(9L, 1L, req, 100L, 7L));

        assertEquals("10001", ex.getCode());
        assertTrue(ex.getMessage().contains("gatePolicyJson"));
        verify(stepDao, never()).update(any(SdlcStepDO.class));
    }

    @Test
    void updateStep_keeps_existing_valid_json_when_request_omits_fields() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setChecklistJson("[\"a\"]");
        step.setGatePolicyJson("{\"b\":1}");
        SdlcStepDO updated = new SdlcStepDO();
        updated.setId(1L);
        updated.setSdlcId(9L);
        updated.setStepOrder(1);
        updated.setName("new");
        updated.setChecklistJson("[\"a\"]");
        updated.setGatePolicyJson("{\"b\":1}");
        when(stepDao.findById(1L)).thenReturn(step).thenReturn(updated);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setName("new");
        StepVO vo = service.updateStep(9L, 1L, req, 100L, 7L);

        assertEquals("new", vo.getName());
        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "new".equals(st.getName())
                && "[\"a\"]".equals(st.getChecklistJson())
                && "{\"b\":1}".equals(st.getGatePolicyJson())));
    }

    @Test
    void addStep_invalid_checklistJson_throws_param_error_and_skips_insert() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        CreateStepRequest req = new CreateStepRequest();
        req.setName("new step");
        req.setChecklistJson("确认需求边界");

        BizException ex = assertThrows(BizException.class,
                () -> service.addStep(9L, req, 100L, 7L));

        assertEquals("10001", ex.getCode());
        assertTrue(ex.getMessage().contains("checklistJson"));
        verify(stepDao, never()).insert(any(SdlcStepDO.class));
    }

    @Test
    void addStep_invalid_gatePolicyJson_throws_param_error_and_skips_insert() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        CreateStepRequest req = new CreateStepRequest();
        req.setName("new step");
        req.setGatePolicyJson("{\"coverageThreshold\":80");

        BizException ex = assertThrows(BizException.class,
                () -> service.addStep(9L, req, 100L, 7L));

        assertEquals("10001", ex.getCode());
        assertTrue(ex.getMessage().contains("gatePolicyJson"));
        verify(stepDao, never()).insert(any(SdlcStepDO.class));
    }

    @Test
    void updateStep_blank_checklistJson_clears_field_to_null_without_conflict() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setChecklistJson("[\"a\"]");
        step.setGatePolicyJson("{\"b\":1}");
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setChecklistJson("");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "old".equals(st.getName())
                && st.getChecklistJson() == null
                && "{\"b\":1}".equals(st.getGatePolicyJson())));
    }

    @Test
    void updateStep_whitespace_gatePolicyJson_clears_field_to_null_without_conflict() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(1L);
        step.setSdlcId(9L);
        step.setStepOrder(1);
        step.setName("old");
        step.setChecklistJson("[\"a\"]");
        step.setGatePolicyJson("{\"b\":1}");
        when(stepDao.findById(1L)).thenReturn(step);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setGatePolicyJson("   ");
        service.updateStep(9L, 1L, req, 100L, 7L);

        verify(stepDao).update(argThat((SdlcStepDO st) -> stepWrite(st)
                && "old".equals(st.getName())
                && "[\"a\"]".equals(st.getChecklistJson())
                && st.getGatePolicyJson() == null));
    }

    @Test
    void addStep_blank_json_fields_are_normalized_to_null() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        CreateStepRequest req = new CreateStepRequest();
        req.setName("new step");
        req.setChecklistJson("");
        req.setGatePolicyJson(" ");

        service.addStep(9L, req, 100L, 7L);

        verify(stepDao).insert(argThat(step ->
                step.getChecklistJson() == null && step.getGatePolicyJson() == null));
    }
}
