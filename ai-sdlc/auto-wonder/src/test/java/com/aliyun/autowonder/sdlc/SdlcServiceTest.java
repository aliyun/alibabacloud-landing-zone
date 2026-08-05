package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.sdlc.dto.*;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SdlcServiceTest {

    SdlcDao sdlcDao;
    SdlcStepDao stepDao;
    StatusNodeDao statusNodeDao;
    WorkitemDao workitemDao;
    SdlcService service;

    @BeforeEach
    void setUp() {
        sdlcDao = mock(SdlcDao.class);
        stepDao = mock(SdlcStepDao.class);
        statusNodeDao = mock(StatusNodeDao.class);
        workitemDao = mock(WorkitemDao.class);
        service = new SdlcService(sdlcDao, stepDao, statusNodeDao, workitemDao);
    }

    private SdlcDO sdlc(long id, String status) {
        SdlcDO s = new SdlcDO();
        s.setId(id);
        s.setName("flow");
        s.setStatus(status);
        s.setVersion(0);
        return s;
    }

    @Test
    void create_sets_draft_status() {
        CreateSdlcRequest req = new CreateSdlcRequest();
        req.setName("Test Flow");
        req.setWorkType("REQ");

        SdlcVO vo = service.create(req, 100L, 7L);

        assertEquals("DRAFT", vo.getStatus());
        assertEquals("Test Flow", vo.getName());
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
        verify(stepDao).update(eq(1L), eq(100L), eq("updated"),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), eq(7L));
        verify(stepDao).softDelete(1L, 100L, 7L);
        verify(stepDao, times(2)).updateOrder(2L, 100L, 1, 7L);
        verify(stepDao, times(2)).updateOrder(3L, 100L, 2, 7L);
    }

    @Test
    void delete_in_use_throws() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        when(workitemDao.countBySdlcId(9L)).thenReturn(1);
        BizException ex = assertThrows(BizException.class, () -> service.delete(9L, 100L, 7L));
        assertEquals("16010", ex.getCode());
    }

    @Test
    void list_maps_to_vos() {
        SdlcDO s = sdlc(1L, "DRAFT");
        when(sdlcDao.list(eq("REQ"), isNull(), eq(0), eq(20))).thenReturn(List.of(s));
        List<SdlcVO> vos = service.list("REQ", null, 1, 20);
        assertEquals(1, vos.size());
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
        when(stepDao.update(eq(1L), eq(100L), eq("new"),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), eq(7L))).thenReturn(1);

        UpdateStepRequest req = new UpdateStepRequest();
        req.setName("new");
        req.setHandlerType("AGENT");
        StepVO vo = service.updateStep(9L, 1L, req, 100L, 7L);
        assertEquals("new", vo.getName());
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

        verify(stepDao).update(eq(1L), eq(100L), eq("new"),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), eq(7L));
        verify(stepDao).updateOrder(2L, 100L, 1, 7L);
        verify(stepDao).updateOrder(1L, 100L, 2, 7L);
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
    void enable_step_order_gap_throws() {
        SdlcDO s = sdlc(9L, "DRAFT");
        when(sdlcDao.findById(9L)).thenReturn(s);
        SdlcStepDO s1 = new SdlcStepDO();
        s1.setId(1L); s1.setSdlcId(9L); s1.setStepOrder(1);
        s1.setName("a"); s1.setHandlerType("AGENT");
        SdlcStepDO s2 = new SdlcStepDO();
        s2.setId(2L); s2.setSdlcId(9L); s2.setStepOrder(3);
        s2.setName("b"); s2.setHandlerType("HUMAN");
        when(stepDao.listBySdlc(9L)).thenReturn(List.of(s1, s2));
        BizException ex = assertThrows(BizException.class, () -> service.enable(9L, null, 100L, 7L));
        assertEquals("16005", ex.getCode());
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
}
